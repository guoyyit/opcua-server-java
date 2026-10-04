package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.DataChangeNotification;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.MonitoredItemCreateRequest;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.NotificationMessage;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A subscription owns monitored items, one single-threaded sampling/publishing
 * scheduler (notifications are never reordered and publish sequence numbers
 * never gap), a bounded retention window for republish and a FIFO of pending
 * publish calls belonging to its own session.
 *
 * <p>Because each subscription has an independent scheduler/queue, a slow
 * consumer of one session never blocks notification delivery for another.
 */
public final class Subscription {

    private static final long TICK_MS = 5;

    private final long subscriptionId;
    private final String sessionToken;
    private final long publishingIntervalMs;
    private final long lifetimeMs;
    private final long retentionWindowMs;
    private final AddressSpace addressSpace;
    private final OpcUaStatsSink statsSink;
    private final int maxMonitoredItems;
    private final int maxRetainedNotifications;

    private final Map<Long, MonitoredItem> monitoredItems = new ConcurrentHashMap<>();
    private final AtomicLong itemIdGenerator = new AtomicLong(0);

    private final ConcurrentLinkedDeque<PendingPublish> pendingPublishes = new ConcurrentLinkedDeque<>();
    private final Map<Long, RetainedNotification> retained = new ConcurrentHashMap<>();
    private final AtomicLong publishSequenceGenerator = new AtomicLong(1);
    private volatile long lastDeliveredSequence = 0;

    private final ScheduledExecutorService scheduler;
    private volatile long lastPublishingCycleMs;
    private volatile boolean shutdown;

    public Subscription(long subscriptionId,
                 String sessionToken,
                 long publishingIntervalMs,
                 long lifetimeMs,
                 long retentionWindowMs,
                 AddressSpace addressSpace,
                 OpcUaStatsSink statsSink,
                 int maxMonitoredItems,
                 int maxRetainedNotifications) {
        this.subscriptionId = subscriptionId;
        this.sessionToken = sessionToken;
        this.publishingIntervalMs = publishingIntervalMs;
        this.lifetimeMs = lifetimeMs;
        this.retentionWindowMs = retentionWindowMs;
        this.addressSpace = addressSpace;
        this.statsSink = statsSink;
        this.maxMonitoredItems = maxMonitoredItems;
        this.maxRetainedNotifications = maxRetainedNotifications;
        this.lastPublishingCycleMs = System.currentTimeMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "opcua-sub-" + subscriptionId);
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            expirePendingPublishes(now);
            for (MonitoredItem item : monitoredItems.values()) {
                item.sample(now);
            }
            if (now - lastPublishingCycleMs >= publishingIntervalMs) {
                lastPublishingCycleMs = now;
                publishCycle(now);
            }
            evictExpiredRetention(now);
        } catch (RuntimeException e) {
            // The scheduler must keep running even when one cycle fails.
        }
    }

    private void publishCycle(long now) {
        List<DataChangeNotification> all = new ArrayList<>();
        for (MonitoredItem item : monitoredItems.values()) {
            all.addAll(item.drain());
        }
        if (all.isEmpty()) {
            return;
        }
        NotificationMessage message =
                new NotificationMessage(0, Instant.now(), List.copyOf(all), false);
        deliver(message, now);
    }

    private void deliver(NotificationMessage message, long now) {
        // Every published notification is retained within the retention window
        // regardless of whether it was delivered live or sat waiting, so a
        // client can republish by sequence number after a reconnect.
        long sequence = publishSequenceGenerator.getAndIncrement();
        NotificationMessage stamped = withSequence(message, sequence);
        retained.put(sequence, new RetainedNotification(sequence, now, stamped));
        trimRetention(now);

        PendingPublish waiter = pollUnexpiredWaiter(now);
        if (waiter != null) {
            // Live delivery. The retained copy above remains for republish.
            lastDeliveredSequence = sequence;
            waiter.future.complete(stamped);
            statsSink.pendingPublishDelta(-1);
            return;
        }
        // Nobody is waiting: the message stays retained until a Publish call
        // asks for the next not-yet-delivered sequence.
    }

    private NotificationMessage withSequence(NotificationMessage message, long sequence) {
        return new NotificationMessage(sequence, message.publishTime(), message.notifications(),
                message.keepAlive());
    }

    private PendingPublish pollUnexpiredWaiter(long now) {
        while (true) {
            PendingPublish waiter = pendingPublishes.poll();
            if (waiter == null) {
                return null;
            }
            if (waiter.deadlineMs > now) {
                return waiter;
            }
            statsSink.pendingPublishDelta(-1);
            waiter.future.complete(new NotificationMessage(0, Instant.now(), List.of(), true));
        }
    }

    private void expirePendingPublishes(long now) {
        Iterator<PendingPublish> iterator = pendingPublishes.iterator();
        while (iterator.hasNext()) {
            PendingPublish waiter = iterator.next();
            if (waiter.deadlineMs <= now) {
                iterator.remove();
                statsSink.pendingPublishDelta(-1);
                waiter.future.complete(new NotificationMessage(0, Instant.now(), List.of(), true));
            }
        }
    }

    private void evictExpiredRetention(long now) {
        retained.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs() > retentionWindowMs);
    }

    private void trimRetention(long now) {
        // Evict expired first, then cap the total retained depth.
        evictExpiredRetention(now);
        while (retained.size() > maxRetainedNotifications) {
            Long oldest = retained.keySet().stream().min(Long::compareTo).orElse(null);
            if (oldest == null) {
                break;
            }
            retained.remove(oldest);
        }
    }

    public long subscriptionId() {
        return subscriptionId;
    }

    public String sessionToken() {
        return sessionToken;
    }

    public long publishingIntervalMs() {
        return publishingIntervalMs;
    }

    public long lifetimeMs() {
        return lifetimeMs;
    }

    public int retainedCount() {
        return retained.size();
    }

    public MonitoredItem createMonitoredItem(MonitoredItemCreateRequest request) {
        if (monitoredItems.size() >= maxMonitoredItems) {
            throw new OpcUaException(StatusCode.BAD_TOO_MANY_MONITORED_ITEMS);
        }
        if (request.queueSize() <= 0) {
            throw new OpcUaException(StatusCode.BAD_MONITORED_ITEM_QUEUE_SIZE_INVALID);
        }
        long itemId = itemIdGenerator.incrementAndGet();
        MonitoredItem item = new MonitoredItem(itemId, request.nodeId(), request.samplingIntervalMs(),
                request.queueSize(), request.discardPolicy(), addressSpace);
        monitoredItems.put(itemId, item);
        statsSink.monitoredItemDelta(1);
        return item;
    }

    public MonitoredItem requireMonitoredItem(long monitoredItemId) {
        MonitoredItem item = monitoredItems.get(monitoredItemId);
        if (item == null) {
            throw new OpcUaException(StatusCode.BAD_MONITORED_ITEM_ID_INVALID);
        }
        return item;
    }

    public int monitoredItemCount() {
        return monitoredItems.size();
    }

    public NotificationMessage republish(long sequenceNumber) {
        RetainedNotification notification = retained.get(sequenceNumber);
        long now = System.currentTimeMillis();
        if (notification == null || now - notification.createdAtMs() > retentionWindowMs) {
            if (notification != null) {
                retained.remove(sequenceNumber);
            }
            throw new OpcUaException(StatusCode.BAD_MESSAGE_NOT_AVAILABLE);
        }
        return notification.message();
    }

    public CompletableFuture<NotificationMessage> enqueuePublish(long timeoutHintMs) {
        if (shutdown) {
            throw new OpcUaException(StatusCode.BAD_SUBSCRIPTION_ID_INVALID);
        }
        long deadline = System.currentTimeMillis() + Math.max(timeoutHintMs, 1);
        PendingPublish waiter = new PendingPublish(deadline, new CompletableFuture<>());
        pendingPublishes.addLast(waiter);
        statsSink.pendingPublishDelta(1);

        // Hand over the next retained notification that this subscription has
        // not delivered via a normal Publish yet. Already-delivered messages
        // are only reachable through explicit Republish within the window.
        deliverNextUndeliveredTo(waiter);
        return waiter.future;
    }

    private void deliverNextUndeliveredTo(PendingPublish waiter) {
        long alreadyDelivered = lastDeliveredSequence;
        Long next = retained.keySet().stream()
                .filter(sequence -> sequence > alreadyDelivered)
                .min(Long::compareTo).orElse(null);
        if (next != null && pendingPublishes.remove(waiter)) {
            statsSink.pendingPublishDelta(-1);
            lastDeliveredSequence = next;
            waiter.future.complete(retained.get(next).message());
        }
    }

    public void cancelPendingPublishes() {
        PendingPublish waiter;
        while ((waiter = pendingPublishes.poll()) != null) {
            statsSink.pendingPublishDelta(-1);
            waiter.future.cancel(false);
        }
    }

    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        cancelPendingPublishes();
        monitoredItems.clear();
        retained.clear();
        scheduler.shutdownNow();
    }

    /**
     * Number of monitored items at shutdown time; the manager decrements global
     * counters using this snapshot before the scheduler is torn down.
     */
    public int drainMonitoredItemStats() {
        int count = monitoredItems.size();
        return count;
    }

    private record PendingPublish(long deadlineMs, CompletableFuture<NotificationMessage> future) {
    }

    interface OpcUaStatsSink {
        void pendingPublishDelta(int delta);

        void monitoredItemDelta(int delta);
    }
}
