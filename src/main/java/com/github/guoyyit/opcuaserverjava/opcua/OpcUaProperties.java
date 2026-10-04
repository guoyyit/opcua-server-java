package com.github.guoyyit.opcuaserverjava.opcua;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable limits and timing parameters of the loopback OPC UA server.
 */
@ConfigurationProperties(prefix = "opcua")
public class OpcUaProperties {

    private int port = 0;
    private long defaultChannelLifetimeMs = 60_000;
    private long defaultSessionTimeoutMs = 60_000;
    private long reaperIntervalMs = 100;
    private int maxSessions = 64;
    private int maxSubscriptionsPerSession = 16;
    private int maxMonitoredItemsPerSubscription = 64;
    private int maxNotificationQueueDepth = 1_000;
    private long minSamplingIntervalMs = 10;
    private long maxContinuationPoints = 32;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getDefaultChannelLifetimeMs() {
        return defaultChannelLifetimeMs;
    }

    public void setDefaultChannelLifetimeMs(long defaultChannelLifetimeMs) {
        this.defaultChannelLifetimeMs = defaultChannelLifetimeMs;
    }

    public long getDefaultSessionTimeoutMs() {
        return defaultSessionTimeoutMs;
    }

    public void setDefaultSessionTimeoutMs(long defaultSessionTimeoutMs) {
        this.defaultSessionTimeoutMs = defaultSessionTimeoutMs;
    }

    public long getReaperIntervalMs() {
        return reaperIntervalMs;
    }

    public void setReaperIntervalMs(long reaperIntervalMs) {
        this.reaperIntervalMs = reaperIntervalMs;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public int getMaxSubscriptionsPerSession() {
        return maxSubscriptionsPerSession;
    }

    public void setMaxSubscriptionsPerSession(int maxSubscriptionsPerSession) {
        this.maxSubscriptionsPerSession = maxSubscriptionsPerSession;
    }

    public int getMaxMonitoredItemsPerSubscription() {
        return maxMonitoredItemsPerSubscription;
    }

    public void setMaxMonitoredItemsPerSubscription(int maxMonitoredItemsPerSubscription) {
        this.maxMonitoredItemsPerSubscription = maxMonitoredItemsPerSubscription;
    }

    public int getMaxNotificationQueueDepth() {
        return maxNotificationQueueDepth;
    }

    public void setMaxNotificationQueueDepth(int maxNotificationQueueDepth) {
        this.maxNotificationQueueDepth = maxNotificationQueueDepth;
    }

    public long getMinSamplingIntervalMs() {
        return minSamplingIntervalMs;
    }

    public void setMinSamplingIntervalMs(long minSamplingIntervalMs) {
        this.minSamplingIntervalMs = minSamplingIntervalMs;
    }

    public long getMaxContinuationPoints() {
        return maxContinuationPoints;
    }

    public void setMaxContinuationPoints(long maxContinuationPoints) {
        this.maxContinuationPoints = maxContinuationPoints;
    }
}
