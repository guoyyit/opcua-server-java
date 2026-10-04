package com.github.guoyyit.opcuaserverjava.opcua.core;

import java.util.List;

/**
 * Publish 请求的返回：订阅标识、本次消息、以及尚未确认的可用序列号。
 */
public final class PublishResult {

    private final long subscriptionId;
    private final NotificationMessage message;
    private final List<Long> availableSequenceNumbers;
    private final boolean keepAlive;

    public PublishResult(long subscriptionId, NotificationMessage message,
                         List<Long> availableSequenceNumbers, boolean keepAlive) {
        this.subscriptionId = subscriptionId;
        this.message = message;
        this.availableSequenceNumbers = availableSequenceNumbers;
        this.keepAlive = keepAlive;
    }

    public long getSubscriptionId() {
        return subscriptionId;
    }

    public NotificationMessage getMessage() {
        return message;
    }

    public List<Long> getAvailableSequenceNumbers() {
        return availableSequenceNumbers;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }
}
