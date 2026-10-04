package com.github.guoyyit.opcuaserverjava.opcua.subscription;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.AddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.addressspace.Node;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.core.NotificationMessage;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.PublishResult;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 订阅/监控项/发布/重发布核心服务。
 *
 * <p>采样与发布解耦：
 * <ul>
 *   <li>单一调度线程负责“到期采样 → 按发布周期打包成消息 → 超窗回收”，
 *       因此消息序列号在订阅内天然连续不乱序；</li>
 *   <li>Publish 调用按“会话”条件等待：慢消费只挂在自己会话的等待队列上，
 *       其他会话照常投递；</li>
 *   <li>未确认消息在保留窗口（时间+条数）内可通过 Republish 取回。</li>
 * </ul>
 */
public class SubscriptionService {

    private final AddressSpace addressSpace;
    private final LongSupplier clock;

    private final int maxMonitoredItemsPerSession;
    private final int maxNotificationQueueDepthPerSession;
    private final long maxRetainedMessagesPerSubscription;

    private final Map<Long, SubscriptionState> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong subscriptionIdSequence = new AtomicLong();
    private final AtomicLong monitoredItemIdSequence = new AtomicLong();

    /** 每个会话一把等待锁：慢消费者只阻塞自己。 */
    private final Map<Long, SessionPump> pumps = new ConcurrentHashMap<>();

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public SubscriptionService(AddressSpace addressSpace,
                               LongSupplier clock,
                               int maxMonitoredItemsPerSession,
                               int maxNotificationQueueDepthPerSession,
                               long maxRetainedMessagesPerSubscription) {
        this.addressSpace = addressSpace;
        this.clock = clock;
        this.maxMonitoredItemsPerSession = maxMonitoredItemsPerSession;
        this.maxNotificationQueueDepthPerSession = maxNotificationQueueDepthPerSession;
        this.maxRetainedMessagesPerSubscription = maxRetainedMessagesPerSubscription;
    }

    public void start(long tickMillis) {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "opcua-subscription-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::safeTick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private SessionPump pump(long sessionId) {
        return pumps.computeIfAbsent(sessionId, key -> new SessionPump());
    }

    public Subscription createSubscription(long sessionId,
                                           double publishingIntervalMillis,
                                           long retentionWindowMillis) {
        lifecycleLock.lock();
        try {
            long id = subscriptionIdSequence.incrementAndGet();
            SubscriptionState state = new SubscriptionState(id, sessionId,
                    Math.max(1.0d, publishingIntervalMillis),
                    Math.max(1L, retentionWindowMillis),
                    maxRetainedMessagesPerSubscription, clock.getAsLong());
            subscriptions.put(id, state);
            pump(sessionId);
            return new Subscription(id, sessionId,
                    publishingIntervalMillis, retentionWindowMillis);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public MonitoredItem createMonitoredItem(long sessionId,
                                             long subscriptionId,
                                             NodeId nodeId,
                                             long clientHandle,
                                             MonitoringParameters parameters) {
        SubscriptionState state = requireSubscription(sessionId, subscriptionId);
        Node node = addressSpace.requireNode(nodeId);
        if (monitoredItemCountOfSession(sessionId) >= maxMonitoredItemsPerSession) {
            throw new OpcUaException(StatusCode.BAD_TOO_MANY_MONITORED_ITEMS);
        }
        int requestedDepth = parameters.getQueueSize();
        if (requestedDepth <= 0 || requestedDepth > maxNotificationQueueDepthPerSession) {
            throw new OpcUaException(StatusCode.BAD_NOTIFICATION_QUEUE_LIMIT_REACHED);
        }
        if (notificationQueueDepthOfSession(sessionId) + requestedDepth
                > maxNotificationQueueDepthPerSession) {
            throw new OpcUaException(StatusCode.BAD_NOTIFICATION_QUEUE_LIMIT_REACHED);
        }
        long itemId = monitoredItemIdSequence.incrementAndGet();
        MonitoredItemState itemState = new MonitoredItemState(
                itemId, clientHandle, node, parameters, clock.getAsLong());
        synchronized (state) {
            state.addItem(itemState);
        }
        MonitoredItem item = new MonitoredItem(itemId, clientHandle,
                new Subscription(state.id, state.sessionId,
                        state.publishingIntervalMillis, state.retentionWindowMillis),
                node, parameters);
        return item;
    }

    public void deleteMonitoredItem(long sessionId, long subscriptionId, long monitoredItemId) {
        SubscriptionState state = requireSubscription(sessionId, subscriptionId);
        synchronized (state) {
            if (!state.hasItem(monitoredItemId)) {
                throw new OpcUaException(StatusCode.BAD_MONITORED_ITEM_ID_UNKNOWN);
            }
            state.removeItem(monitoredItemId);
        }
    }

    public void deleteSubscription(long sessionId, long subscriptionId) {
        lifecycleLock.lock();
        try {
            SubscriptionState state = requireSubscription(sessionId, subscriptionId);
            subscriptions.remove(subscriptionId);
            pump(sessionId).signal();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 关闭会话时回收其全部订阅并移除会话等待队列，释放全部相关资源。
     */
    public void deleteAllSubscriptionsOfSession(long sessionId) {
        lifecycleLock.lock();
        try {
            List<Long> owned = new ArrayList<>();
            for (SubscriptionState state : subscriptions.values()) {
                if (state.sessionId == sessionId) {
                    owned.add(state.id);
                }
            }
            owned.forEach(subscriptions::remove);
            SessionPump removed = pumps.remove(sessionId);
            if (removed != null) {
                removed.shutdown();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 把订阅迁移到新会话（通道重连场景）：迁移后由新会话消费，
     * 旧会话立即不再持有该订阅，保留消息可继续重发布。
     */
    public Subscription transferSubscription(long oldSessionId,
                                             long newSessionId,
                                             long subscriptionId) {
        lifecycleLock.lock();
        try {
            SubscriptionState state = subscriptions.get(subscriptionId);
            if (state == null) {
                throw new OpcUaException(StatusCode.BAD_SUBSCRIPTION_ID_UNKNOWN);
            }
            if (state.sessionId != oldSessionId) {
                throw new OpcUaException(StatusCode.BAD_SUBSCRIPTION_NOT_TRANSFERRED);
            }
            state.sessionId = newSessionId;
            pump(newSessionId);
            pump(newSessionId).signal();
            pump(oldSessionId).signal();
            return new Subscription(state.id, newSessionId,
                    state.publishingIntervalMillis, state.retentionWindowMillis);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 发布：等待该订阅所属会话出现待投递消息；超时返回 keep-alive 空结果。
     * 不同会话使用独立条件队列，慢消费不阻塞其他会话。
     */
    public PublishResult publish(long sessionId,
                                 long subscriptionId,
                                 long timeoutMillis,
                                 List<Long> acknowledgements) {
        SubscriptionState state = requireSubscription(sessionId, subscriptionId);
        if (acknowledgements != null && !acknowledgements.isEmpty()) {
            synchronized (state) {
                state.acknowledge(acknowledgements);
            }
        }
        long deadline = clock.getAsLong() + Math.max(1L, timeoutMillis);
        long wallDeadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(10L, timeoutMillis));
        SessionPump sessionPump = pump(sessionId);
        sessionPump.awaitSubscriptionReady(state, deadline, clock, wallDeadlineNanos);

        NotificationMessage message;
        synchronized (state) {
            message = state.takePending();
        }
        if (message == null) {
            return new PublishResult(subscriptionId, null, List.of(), true);
        }
        List<Long> available;
        synchronized (state) {
            available = state.availableSequenceNumbers();
        }
        return new PublishResult(subscriptionId, message, available, false);
    }

    public PublishResult republish(long sessionId, long subscriptionId, long sequenceNumber) {
        SubscriptionState state = requireSubscription(sessionId, subscriptionId);
        synchronized (state) {
            NotificationMessage message = state.retainedMessage(sequenceNumber, clock.getAsLong());
            if (message == null) {
                throw new OpcUaException(StatusCode.BAD_MESSAGE_NOT_AVAILABLE);
            }
            return new PublishResult(subscriptionId, message,
                    state.availableSequenceNumbers(), false);
        }
    }

    public void acknowledge(long sessionId, long subscriptionId, List<Long> sequenceNumbers) {
        SubscriptionState state = requireSubscription(sessionId, subscriptionId);
        synchronized (state) {
            state.acknowledge(sequenceNumbers);
        }
    }

    public int activeSubscriptionCount() {
        return subscriptions.size();
    }

    public int monitoredItemCountOfSession(long sessionId) {
        int total = 0;
        for (SubscriptionState state : subscriptions.values()) {
            if (state.sessionId == sessionId) {
                synchronized (state) {
                    total += state.itemCount();
                }
            }
        }
        return total;
    }

    public int notificationQueueDepthOfSession(long sessionId) {
        int total = 0;
        for (SubscriptionState state : subscriptions.values()) {
            if (state.sessionId == sessionId) {
                synchronized (state) {
                    total += state.queueDepth();
                }
            }
        }
        return total;
    }

    /** 供测试确定性驱动的采样/打包/回收入口。 */
    public void tick(long nowMillis) {
        lifecycleLock.lock();
        try {
            Map<Long, List<NotificationMessage>> readyBySession = new HashMap<>();
            for (SubscriptionState state : subscriptions.values()) {
                synchronized (state) {
                    state.sampleDue(nowMillis);
                    state.expireByTime(nowMillis);
                    if (!state.isPublishingDue(nowMillis)) {
                        continue;
                    }
                    state.markPublished(nowMillis);
                    NotificationMessage message = state.packIfReady(nowMillis);
                    if (message != null) {
                        readyBySession
                                .computeIfAbsent(state.sessionId, k -> new ArrayList<>())
                                .add(message);
                    }
                }
            }
            readyBySession.forEach((sessionId, messages) -> {
                SessionPump sessionPump = pumps.get(sessionId);
                if (sessionPump != null) {
                    sessionPump.signal();
                }
            });
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void safeTick() {
        try {
            tick(clock.getAsLong());
        } catch (RuntimeException ignored) {
            // 调度线程绝不能因单次异常终止。
        }
    }

    private SubscriptionState requireSubscription(long sessionId, long subscriptionId) {
        SubscriptionState state = subscriptions.get(subscriptionId);
        if (state == null) {
            throw new OpcUaException(StatusCode.BAD_SUBSCRIPTION_ID_UNKNOWN);
        }
        if (state.sessionId != sessionId) {
            throw new OpcUaException(StatusCode.BAD_SUBSCRIPTION_ID_INVALID);
        }
        return state;
    }

    /**
     * 单会话的发布等待泵：一把锁+条件，会话间完全隔离。
     */
    private static final class SessionPump {
        private final Object lock = new Object();

        void awaitSubscriptionReady(SubscriptionState state, long deadlineMillis,
                                    LongSupplier clock, long wallDeadlineNanos) {
            synchronized (lock) {
                while (clock.getAsLong() < deadlineMillis
                        && System.nanoTime() < wallDeadlineNanos) {
                    boolean ready;
                    synchronized (state) {
                        ready = state.hasPending();
                    }
                    if (ready) {
                        return;
                    }
                    long remaining = deadlineMillis - clock.getAsLong();
                    if (remaining <= 0) {
                        return;
                    }
                    try {
                        lock.wait(Math.min(remaining, 10L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        void signal() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        void shutdown() {
            signal();
        }
    }
}
