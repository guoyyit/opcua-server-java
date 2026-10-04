package com.github.guoyyit.opcuaserverjava.opcua.subscription;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.Node;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;

/**
 * 监控项：绑定一个节点，独立采样间隔/队列大小/丢弃策略。
 */
public class MonitoredItem {

    private final long monitoredItemId;
    private final long clientHandle;
    private final Subscription subscription;
    private final Node node;
    private final MonitoringParameters parameters;

    public MonitoredItem(long monitoredItemId, long clientHandle,
                         Subscription subscription, Node node,
                         MonitoringParameters parameters) {
        this.monitoredItemId = monitoredItemId;
        this.clientHandle = clientHandle;
        this.subscription = subscription;
        this.node = node;
        this.parameters = parameters;
    }

    public long getMonitoredItemId() {
        return monitoredItemId;
    }

    public long getClientHandle() {
        return clientHandle;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public Node getNode() {
        return node;
    }

    public MonitoringParameters getParameters() {
        return parameters;
    }

    public DataValue sample(long nowMillis) {
        return null;
    }
}
