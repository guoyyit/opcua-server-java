package com.github.guoyyit.opcuaserverjava.opcua.channel;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 安全通道：拥有独立的请求/响应序列号空间与超时时间。
 */
public class SecureChannel {

    private final long channelId;
    private final long createdAt;
    private final long lifetimeMillis;
    private final AtomicLong lastActivity;
    private final AtomicLong expectedRequestSequence;
    private final AtomicLong lastResponseSequence;
    private volatile boolean closed;

    public SecureChannel(long channelId, long lifetimeMillis, long nowMillis) {
        this.channelId = channelId;
        this.lifetimeMillis = lifetimeMillis;
        this.createdAt = nowMillis;
        this.lastActivity = new AtomicLong(nowMillis);
        this.expectedRequestSequence = new AtomicLong(1L);
        this.lastResponseSequence = new AtomicLong(0L);
        this.closed = false;
    }

    public long getChannelId() {
        return channelId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLifetimeMillis() {
        return lifetimeMillis;
    }

    public long getLastActivity() {
        return lastActivity.get();
    }

    public long getExpectedRequestSequence() {
        return expectedRequestSequence.get();
    }

    public boolean isExpired(long nowMillis) {
        return !closed && (nowMillis - lastActivity.get()) > lifetimeMillis;
    }

    public boolean isClosed() {
        return closed;
    }

    public void touch(long nowMillis) {
        lastActivity.set(nowMillis);
    }

    public void close() {
        closed = true;
    }

    /**
     * 请求序列号严格连续递增；调用方比较与期望值相等后推进。
     */
    public void advanceRequestSequence() {
        expectedRequestSequence.incrementAndGet();
    }

    public long consumeResponseSequence() {
        return lastResponseSequence.incrementAndGet();
    }
}
