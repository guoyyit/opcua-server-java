package com.github.guoyyit.opcuaserverjava.opcua.core;

import java.util.List;

/**
 * 一次发布的通知消息，序列号在单个订阅内单调递增、连续不丢号。
 */
public final class NotificationMessage {

    private final long sequenceNumber;
    private final long publishTime;
    private final List<MonitoredItemNotification> notifications;

    public NotificationMessage(long sequenceNumber, long publishTime,
                               List<MonitoredItemNotification> notifications) {
        this.sequenceNumber = sequenceNumber;
        this.publishTime = publishTime;
        this.notifications = notifications;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public long getPublishTime() {
        return publishTime;
    }

    public List<MonitoredItemNotification> getNotifications() {
        return notifications;
    }
}
