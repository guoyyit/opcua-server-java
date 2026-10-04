package com.github.guoyyit.opcuaserverjava.opcua.subscription;

import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoredItemNotification;
import com.github.guoyyit.opcuaserverjava.opcua.core.NotificationMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅运行时状态（包内可见）。
 *
 * <p>序列号在订阅内严格单调递增；每次打包产生一条消息即占一个连续号。
 * pending 队列保存尚未投递的消息；retained 映射保存已投递但未确认、
 * 仍在保留窗口内的消息。
 */
final class SubscriptionState {

    final long id;
    volatile long sessionId;
    final double publishingIntervalMillis;
    final long retentionWindowMillis;
    final long maxRetainedMessages;

    private final Map<Long, MonitoredItemState> items = new LinkedHashMap<>();

    /** 尚未被任何 Publish 取走的消息（按序列号升序）。 */
    private final Deque<NotificationMessage> pending = new ArrayDeque<>();
    /** 已取走但尚未确认的消息，供保留窗口内重发布。 */
    private final LinkedHashMap<Long, NotificationMessage> retained = new LinkedHashMap<>();

    private long nextSequenceNumber = 1L;
    private long nextUnacknowledgedSequence = 1L;
    private long lastPublishingTick;

    SubscriptionState(long id, long sessionId, double publishingIntervalMillis,
                      long retentionWindowMillis, long maxRetainedMessages, long nowMillis) {
        this.id = id;
        this.sessionId = sessionId;
        this.publishingIntervalMillis = publishingIntervalMillis;
        this.retentionWindowMillis = retentionWindowMillis;
        this.maxRetainedMessages = maxRetainedMessages;
        this.lastPublishingTick = nowMillis;
    }

    synchronized boolean isPublishingDue(long nowMillis) {
        return nowMillis - lastPublishingTick >= publishingIntervalMillis;
    }

    synchronized void markPublished(long nowMillis) {
        lastPublishingTick = nowMillis;
    }

    synchronized void addItem(MonitoredItemState item) {
        items.put(item.id, item);
    }

    synchronized MonitoredItemState removeItem(long monitoredItemId) {
        return items.remove(monitoredItemId);
    }

    synchronized boolean hasItem(long monitoredItemId) {
        return items.containsKey(monitoredItemId);
    }

    synchronized List<MonitoredItemState> items() {
        return new ArrayList<>(items.values());
    }

    synchronized int itemCount() {
        return items.size();
    }

    /**
     * 对所有到期监控项采样；变化通知缓存在各监控项队列中，等待统一打包。
     */
    synchronized void sampleDue(long nowMillis) {
        for (MonitoredItemState item : items.values()) {
            item.sampleIfDue(nowMillis);
        }
    }

    /**
     * 把各监控项中已缓存的变化打包成一条消息；无变化时不产生消息、不占序列号。
     */
    synchronized NotificationMessage packIfReady(long nowMillis) {
        List<MonitoredItemNotification> batch = new ArrayList<>();
        for (MonitoredItemState item : items.values()) {
            batch.addAll(item.drainQueuedNotifications());
        }
        if (batch.isEmpty()) {
            return null;
        }
        NotificationMessage message =
                new NotificationMessage(nextSequenceNumber++, nowMillis, List.copyOf(batch));
        enqueuePending(message);
        return message;
    }

    private void enqueuePending(NotificationMessage message) {
        pending.addLast(message);
        // 保留总量受窗口条数上限约束，超出时回收最旧消息并推进未确认水位。
        while (totalRetainedCount() > maxRetainedMessages) {
            reclaimOldest();
        }
    }

    private int totalRetainedCount() {
        return pending.size() + retained.size();
    }

    private void reclaimOldest() {
        if (!pending.isEmpty()) {
            NotificationMessage removed = pending.pollFirst();
            advanceWatermarkPast(removed.getSequenceNumber());
        } else if (!retained.isEmpty()) {
            Long oldestKey = retained.keySet().iterator().next();
            retained.remove(oldestKey);
            advanceWatermarkPast(oldestKey);
        }
    }

    private void advanceWatermarkPast(long sequenceNumber) {
        if (sequenceNumber != nextUnacknowledgedSequence) {
            return;
        }
        nextUnacknowledgedSequence++;
        while (retained.containsKey(nextUnacknowledgedSequence)
                || containsPending(nextUnacknowledgedSequence)) {
            nextUnacknowledgedSequence++;
        }
    }

    private boolean containsPending(long sequenceNumber) {
        for (NotificationMessage message : pending) {
            if (message.getSequenceNumber() == sequenceNumber) {
                return true;
            }
        }
        return false;
    }

    /** 取出最旧的待投递消息并转入 retained。 */
    synchronized NotificationMessage takePending() {
        NotificationMessage message = pending.pollFirst();
        if (message != null) {
            retained.put(message.getSequenceNumber(), message);
        }
        return message;
    }

    synchronized boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * 重发布：仅允许取已投递、未确认且在保留时间窗口内的消息。
     */
    synchronized NotificationMessage retainedMessage(long sequenceNumber, long nowMillis) {
        NotificationMessage message = retained.get(sequenceNumber);
        if (message == null) {
            return null;
        }
        if (nowMillis - message.getPublishTime() > retentionWindowMillis) {
            retained.remove(sequenceNumber);
            advanceWatermarkPast(sequenceNumber);
            return null;
        }
        return message;
    }

    /** 确认一批序列号：仍在 retained 中则删除并推进水位。 */
    synchronized void acknowledge(List<Long> sequenceNumbers) {
        for (Long sequenceNumber : sequenceNumbers) {
            if (retained.remove(sequenceNumber) != null) {
                advanceWatermarkPast(sequenceNumber);
            }
        }
    }

    /** 清理超出保留时间窗口的消息。 */
    synchronized int expireByTime(long nowMillis) {
        int[] removed = {0};
        retained.entrySet().removeIf(entry -> {
            boolean expired = nowMillis - entry.getValue().getPublishTime() > retentionWindowMillis;
            if (expired) {
                advanceWatermarkPast(entry.getKey());
                removed[0]++;
            }
            return expired;
        });
        return removed[0];
    }

    synchronized List<Long> availableSequenceNumbers() {
        List<Long> result = new ArrayList<>();
        for (NotificationMessage message : pending) {
            result.add(message.getSequenceNumber());
        }
        result.addAll(retained.keySet());
        return result;
    }

    synchronized int queueDepth() {
        int depth = pending.size() + retained.size();
        for (MonitoredItemState item : items.values()) {
            depth += item.queueCapacity();
        }
        return depth;
    }

    synchronized long lowestSequenceNumber() {
        return nextUnacknowledgedSequence;
    }
}
