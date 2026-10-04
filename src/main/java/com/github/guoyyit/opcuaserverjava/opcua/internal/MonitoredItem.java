package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.DataChangeNotification;
import com.github.guoyyit.opcuaserverjava.opcua.model.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.model.DiscardPolicy;
import com.github.guoyyit.opcuaserverjava.opcua.model.MonitoringMode;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One monitored item: independent sampling interval, bounded queue size and a
 * discard policy. All access is serialized by the owning subscription's single
 * scheduler thread, so no extra locking is needed here.
 */
public final class MonitoredItem {

    private final long itemId;
    private final NodeId nodeId;
    private final AddressSpace addressSpace;

    private long samplingIntervalMs;
    private int queueSize;
    private DiscardPolicy discardPolicy;
    private MonitoringMode monitoringMode = MonitoringMode.REPORTING;

    private final Deque<DataChangeNotification> queue = new ArrayDeque<>();
    private DataValue lastSampled;
    private long lastSampledAtMs;
    private boolean overflow;

    MonitoredItem(long itemId,
                  NodeId nodeId,
                  long samplingIntervalMs,
                  int queueSize,
                  DiscardPolicy discardPolicy,
                  AddressSpace addressSpace) {
        this.itemId = itemId;
        this.nodeId = nodeId;
        this.samplingIntervalMs = samplingIntervalMs;
        this.queueSize = queueSize;
        this.discardPolicy = discardPolicy == null ? DiscardPolicy.DISCARD_OLDEST : discardPolicy;
        this.addressSpace = addressSpace;
    }

    public long itemId() {
        return itemId;
    }

    public NodeId nodeId() {
        return nodeId;
    }

    public long samplingIntervalMs() {
        return samplingIntervalMs;
    }

    public int queueSize() {
        return queueSize;
    }

    public void modify(long samplingIntervalMs, int queueSize) {
        this.samplingIntervalMs = samplingIntervalMs;
        if (queueSize != this.queueSize) {
            this.queueSize = queueSize;
            trimToQueueSize();
        }
    }

    public MonitoringMode monitoringMode() {
        return monitoringMode;
    }

    public void setMonitoringMode(MonitoringMode mode) {
        this.monitoringMode = mode;
        if (mode == MonitoringMode.DISABLED) {
            queue.clear();
            overflow = false;
        }
    }

    /**
     * Samples the node when due. Only value or status changes are queued. The
     * first sample after creation is always reported.
     */
    void sample(long nowMs) {
        if (monitoringMode == MonitoringMode.DISABLED) {
            return;
        }
        if (lastSampled != null && nowMs - lastSampledAtMs < samplingIntervalMs) {
            return;
        }
        lastSampledAtMs = nowMs;
        DataValue current = addressSpace.requireNode(nodeId).dataValue();
        if (lastSampled == null || changed(current)) {
            enqueue(new DataChangeNotification(itemId, nodeId, current, false));
        }
        lastSampled = current;
    }

    private boolean changed(DataValue current) {
        if (lastSampled == null) {
            return true;
        }
        return !java.util.Objects.equals(lastSampled.value(), current.value())
                || lastSampled.statusCode() != current.statusCode();
    }

    private void enqueue(DataChangeNotification notification) {
        if (queue.size() >= queueSize) {
            overflow = true;
            if (discardPolicy == DiscardPolicy.DISCARD_OLDEST) {
                queue.pollFirst();
                queue.addLast(notification);
            }
            // DISCARD_NEWEST: the new notification is dropped.
        } else {
            queue.addLast(notification);
        }
    }

    private void trimToQueueSize() {
        while (queue.size() > queueSize) {
            if (discardPolicy == DiscardPolicy.DISCARD_OLDEST) {
                queue.pollFirst();
            } else {
                queue.pollLast();
            }
        }
    }

    /**
     * Drains all queued notifications for one publishing cycle, stamping the
     * current overflow flag onto the last drained notification.
     */
    List<DataChangeNotification> drain() {
        if (queue.isEmpty()) {
            return List.of();
        }
        List<DataChangeNotification> drained = new ArrayList<>(queue.size());
        while (!queue.isEmpty()) {
            DataChangeNotification notification = queue.pollFirst();
            drained.add(notification);
        }
        if (overflow) {
            int last = drained.size() - 1;
            DataChangeNotification n = drained.get(last);
            drained.set(last, new DataChangeNotification(n.monitoredItemId(), n.nodeId(), n.value(), true));
            overflow = false;
        }
        return drained;
    }

    boolean hasData() {
        return !queue.isEmpty();
    }
}
