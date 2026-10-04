package com.github.guoyyit.opcuaserverjava.opcua.subscription;

/**
 * 订阅：归属于一个会话，包含发布周期、保留窗口与单调递增消息序列。
 */
public class Subscription {

    private final long subscriptionId;
    private final long owningSessionId;
    private final double publishingIntervalMillis;
    private final long retentionWindowMillis;

    public Subscription(long subscriptionId, long owningSessionId,
                        double publishingIntervalMillis, long retentionWindowMillis) {
        this.subscriptionId = subscriptionId;
        this.owningSessionId = owningSessionId;
        this.publishingIntervalMillis = publishingIntervalMillis;
        this.retentionWindowMillis = retentionWindowMillis;
    }

    public long getSubscriptionId() {
        return subscriptionId;
    }

    public long getOwningSessionId() {
        return owningSessionId;
    }

    public double getPublishingIntervalMillis() {
        return publishingIntervalMillis;
    }

    public long getRetentionWindowMillis() {
        return retentionWindowMillis;
    }
}
