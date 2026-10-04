package com.github.guoyyit.opcuaserverjava.opcua.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OPC UA 服务端可配置参数（前缀 opcua.server）。
 */
@ConfigurationProperties(prefix = "opcua.server")
public class OpcUaProperties {

    private long defaultChannelLifetimeMillis = 60_000L;
    private long defaultSessionTimeoutMillis = 30_000L;
    private int maxSessions = 100;
    private int maxMonitoredItemsPerSession = 200;
    private int maxNotificationQueueDepthPerSession = 2_000;
    private long reaperIntervalMillis = 200L;
    private double schedulerTickMillis = 5.0d;
    private long retentionWindowMillis = 60_000L;
    private double defaultPublishingIntervalMillis = 50.0d;

    public long getDefaultChannelLifetimeMillis() {
        return defaultChannelLifetimeMillis;
    }

    public void setDefaultChannelLifetimeMillis(long value) {
        this.defaultChannelLifetimeMillis = value;
    }

    public long getDefaultSessionTimeoutMillis() {
        return defaultSessionTimeoutMillis;
    }

    public void setDefaultSessionTimeoutMillis(long value) {
        this.defaultSessionTimeoutMillis = value;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public int getMaxMonitoredItemsPerSession() {
        return maxMonitoredItemsPerSession;
    }

    public void setMaxMonitoredItemsPerSession(int maxMonitoredItemsPerSession) {
        this.maxMonitoredItemsPerSession = maxMonitoredItemsPerSession;
    }

    public int getMaxNotificationQueueDepthPerSession() {
        return maxNotificationQueueDepthPerSession;
    }

    public void setMaxNotificationQueueDepthPerSession(int value) {
        this.maxNotificationQueueDepthPerSession = value;
    }

    public long getReaperIntervalMillis() {
        return reaperIntervalMillis;
    }

    public void setReaperIntervalMillis(long reaperIntervalMillis) {
        this.reaperIntervalMillis = reaperIntervalMillis;
    }

    public double getSchedulerTickMillis() {
        return schedulerTickMillis;
    }

    public void setSchedulerTickMillis(double schedulerTickMillis) {
        this.schedulerTickMillis = schedulerTickMillis;
    }

    public long getRetentionWindowMillis() {
        return retentionWindowMillis;
    }

    public void setRetentionWindowMillis(long retentionWindowMillis) {
        this.retentionWindowMillis = retentionWindowMillis;
    }

    public double getDefaultPublishingIntervalMillis() {
        return defaultPublishingIntervalMillis;
    }

    public void setDefaultPublishingIntervalMillis(double defaultPublishingIntervalMillis) {
        this.defaultPublishingIntervalMillis = defaultPublishingIntervalMillis;
    }
}
