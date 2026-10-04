package com.github.guoyyit.opcuaserverjava.opcua.subscription;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.Node;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoredItemNotification;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 监控项运行时状态（包内可见）。
 *
 * <p>每个监控项按自己的采样间隔到期采样；仅当值发生变化时入通知队列。
 * 队列满时按 DISCARD_OLDEST / DISCARD_NEWEST 处理。
 * 通知只在此处短暂缓冲，下一次订阅打包即清空，避免跨周期堆积。
 */
final class MonitoredItemState {

    final long id;
    final long clientHandle;
    final Node node;
    final MonitoringParameters parameters;

    private final Deque<MonitoredItemNotification> queue = new ArrayDeque<>();
    private long lastSampleTick;
    private Object lastSampledValue = INITIAL_SENTINEL;
    private volatile boolean overflow;

    MonitoredItemState(long id, long clientHandle, Node node,
                       MonitoringParameters parameters, long nowMillis) {
        this.id = id;
        this.clientHandle = clientHandle;
        this.node = node;
        this.parameters = parameters;
        this.lastSampleTick = nowMillis;
    }

    boolean isSampleDue(long nowMillis) {
        return nowMillis - lastSampleTick >= parameters.getSamplingIntervalMillis();
    }

    void sampleIfDue(long nowMillis) {
        if (!isSampleDue(nowMillis)) {
            return;
        }
        lastSampleTick = nowMillis;
        DataValue dataValue = node.getDataValue();
        if (dataValue == null) {
            return;
        }
        Object current = dataValue.getValue();
        if (lastSampledValue != INITIAL_SENTINEL && Objects.equals(current, lastSampledValue)) {
            return;
        }
        lastSampledValue = current;
        enqueue(new MonitoredItemNotification(clientHandle, id, dataValue));
    }

    private static final Object INITIAL_SENTINEL = new Object();

    private void enqueue(MonitoredItemNotification notification) {
        synchronized (queue) {
            if (queue.size() >= parameters.getQueueSize()) {
                if (parameters.getDiscardPolicy() == MonitoringParameters.DiscardPolicy.DISCARD_OLDEST) {
                    queue.pollFirst();
                    queue.addLast(notification);
                    overflow = true;
                }
                // DISCARD_NEWEST：直接丢弃本次通知，保留旧值。
                overflow = true;
                return;
            }
            queue.addLast(notification);
        }
    }

    List<MonitoredItemNotification> drainQueuedNotifications() {
        synchronized (queue) {
            if (queue.isEmpty()) {
                return List.of();
            }
            List<MonitoredItemNotification> drained = new ArrayList<>(queue);
            queue.clear();
            overflow = false;
            return drained;
        }
    }

    int queueCapacity() {
        return parameters.getQueueSize();
    }

    boolean isOverflow() {
        return overflow;
    }
}
