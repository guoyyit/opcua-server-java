package com.github.guoyyit.opcuaserverjava.opcua.session;

import com.github.guoyyit.opcuaserverjava.opcua.channel.SecureChannel;
import com.github.guoyyit.opcuaserverjava.opcua.security.UserIdentity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话：绑定到安全通道，激活后才能使用；跟踪空闲时间与拥有的订阅。
 */
public class Session {

    private final long sessionId;
    private final SecureChannel channel;
    private final long sessionTimeoutMillis;
    private final long createdAt;
    private volatile long lastActivity;
    private volatile boolean activated;
    private volatile boolean closed;
    private volatile UserIdentity identity;
    private final Set<Long> subscriptionIds = ConcurrentHashMap.newKeySet();

    public Session(long sessionId, SecureChannel channel, long sessionTimeoutMillis, long nowMillis) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        this.createdAt = nowMillis;
        this.lastActivity = nowMillis;
    }

    public long getSessionId() {
        return sessionId;
    }

    public SecureChannel getChannel() {
        return channel;
    }

    public long getSessionTimeoutMillis() {
        return sessionTimeoutMillis;
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public boolean isActivated() {
        return activated;
    }

    public boolean isClosed() {
        return closed;
    }

    public UserIdentity getIdentity() {
        return identity;
    }

    public Set<Long> getSubscriptionIds() {
        return subscriptionIds;
    }

    public void touch(long nowMillis) {
        lastActivity = nowMillis;
    }

    public void activate(UserIdentity userIdentity, long nowMillis) {
        this.identity = userIdentity;
        this.activated = true;
        this.lastActivity = nowMillis;
    }

    public void close() {
        closed = true;
        activated = false;
    }

    /**
     * 幂等关闭：仅首次调用返回 true，保证级联回收只执行一次。
     */
    public synchronized boolean closeIfOpen() {
        if (closed) {
            return false;
        }
        closed = true;
        activated = false;
        return true;
    }

    public boolean isExpired(long nowMillis) {
        return !closed && (nowMillis - lastActivity) > sessionTimeoutMillis;
    }
}
