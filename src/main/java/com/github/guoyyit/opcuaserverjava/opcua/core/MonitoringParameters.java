package com.github.guoyyit.opcuaserverjava.opcua.core;

/**
 * 监控项参数：采样间隔（毫秒）、队列大小、满队列丢弃策略。
 */
public final class MonitoringParameters {

    public enum DiscardPolicy {
        /** 丢弃最旧通知，保留最新值。 */
        DISCARD_OLDEST,
        /** 丢弃最新通知，保留最旧值（触发队列满状态）。 */
        DISCARD_NEWEST
    }

    private final double samplingIntervalMillis;
    private final int queueSize;
    private final DiscardPolicy discardPolicy;

    public MonitoringParameters(double samplingIntervalMillis, int queueSize, DiscardPolicy discardPolicy) {
        this.samplingIntervalMillis = samplingIntervalMillis;
        this.queueSize = queueSize;
        this.discardPolicy = discardPolicy;
    }

    public double getSamplingIntervalMillis() {
        return samplingIntervalMillis;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public DiscardPolicy getDiscardPolicy() {
        return discardPolicy;
    }
}
