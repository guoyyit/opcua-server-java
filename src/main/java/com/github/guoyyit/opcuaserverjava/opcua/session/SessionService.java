package com.github.guoyyit.opcuaserverjava.opcua.session;

import com.github.guoyyit.opcuaserverjava.opcua.channel.SecureChannel;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.UserIdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.security.IdentityValidator;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 会话生命周期管理：创建、激活（匿名/凭据）、关闭、空闲失效清扫。
 * 会话或通道失效时，通过 onSessionInvalid 回调回收订阅等关联资源。
 */
public class SessionService {

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Long, java.util.Set<Long>> sessionsByChannel = new ConcurrentHashMap<>();
    private final AtomicLong sessionIdSequence = new AtomicLong(0L);
    private final IdentityValidator identityValidator;
    private final LongSupplier clock;
    private final long maxSessionTimeoutMillis;
    private final int maxSessions;
    private Consumer<Session> onSessionInvalid;

    public SessionService(IdentityValidator identityValidator,
                          LongSupplier clock,
                          long maxSessionTimeoutMillis,
                          int maxSessions) {
        this.identityValidator = identityValidator;
        this.clock = clock;
        this.maxSessionTimeoutMillis = maxSessionTimeoutMillis;
        this.maxSessions = maxSessions;
    }

    public void setOnSessionInvalid(Consumer<Session> onSessionInvalid) {
        this.onSessionInvalid = onSessionInvalid;
    }

    public Session createSession(SecureChannel channel, long requestedTimeoutMillis) {
        if (activeSessionCount() >= maxSessions) {
            throw new OpcUaException(StatusCode.BAD_SESSION_LIMIT_REACHED);
        }
        long timeout = Math.max(1L, Math.min(requestedTimeoutMillis, maxSessionTimeoutMillis));
        long sessionId = sessionIdSequence.incrementAndGet();
        Session session = new Session(sessionId, channel, timeout, clock.getAsLong());
        sessions.put(sessionId, session);
        sessionsByChannel
                .computeIfAbsent(channel.getChannelId(), key -> ConcurrentHashMap.newKeySet())
                .add(sessionId);
        return session;
    }

    public Session activateSession(long sessionId, UserIdentityToken identityToken) {
        Session session = requireExistingSession(sessionId);
        // 激活失败不得改变会话状态，也不泄露用户/口令校验细节。
        var identity = identityValidator.validate(identityToken);
        session.activate(identity, clock.getAsLong());
        return session;
    }

    /**
     * 会话必须存在、未关闭、未空闲超时、已激活，且仍绑定在当前通道上。
     */
    public Session requireUsableSession(long sessionId, SecureChannel channel) {
        Session session = requireExistingSession(sessionId);
        long now = clock.getAsLong();
        if (session.isExpired(now)) {
            invalidate(session, StatusCode.BAD_SESSION_TIMEOUT);
            throw new OpcUaException(StatusCode.BAD_SESSION_TIMEOUT);
        }
        if (session.getChannel().getChannelId() != channel.getChannelId()
                || session.getChannel().isClosed()) {
            throw new OpcUaException(StatusCode.BAD_SECURE_CHANNEL_ID_INVALID);
        }
        if (!session.isActivated()) {
            throw new OpcUaException(StatusCode.BAD_SESSION_NOT_ACTIVATED);
        }
        session.touch(now);
        return session;
    }

    public void closeSession(long sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new OpcUaException(StatusCode.BAD_SESSION_ID_INVALID);
        }
        invalidate(session, StatusCode.BAD_SESSION_CLOSED);
    }

    public int reapExpiredSessions(long nowMillis) {
        int reaped = 0;
        Iterator<Map.Entry<Long, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next().getValue();
            if (session.isExpired(nowMillis)) {
                invalidate(session, StatusCode.BAD_SESSION_TIMEOUT);
                reaped++;
            }
        }
        return reaped;
    }

    public int activeSessionCount() {
        return (int) sessions.values().stream().filter(s -> !s.isClosed()).count();
    }

    private Session requireExistingSession(long sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new OpcUaException(StatusCode.BAD_SESSION_ID_INVALID);
        }
        if (session.isClosed()) {
            throw new OpcUaException(StatusCode.BAD_SESSION_CLOSED);
        }
        return session;
    }

    private void invalidate(Session session, StatusCode reason) {
        if (session.closeIfOpen()) {
            sessions.remove(session.getSessionId());
            java.util.Set<Long> onChannel =
                    sessionsByChannel.get(session.getChannel().getChannelId());
            if (onChannel != null) {
                onChannel.remove(session.getSessionId());
            }
            if (onSessionInvalid != null) {
                onSessionInvalid.accept(session);
            }
        }
    }

    /**
     * 通道关闭时由门面调用：幂等关闭该通道上的所有会话。
     */
    public void closeAllSessionsOnChannel(long channelId) {
        java.util.Set<Long> ids = sessionsByChannel.get(channelId);
        if (ids == null) {
            return;
        }
        for (Long sessionId : List.copyOf(ids)) {
            closeSessionQuietly(sessionId);
        }
        sessionsByChannel.remove(channelId);
    }

    /** 级联回收路径使用：会话已不存在时不抛错。 */
    public void closeSessionQuietly(long sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            invalidate(session, StatusCode.BAD_SESSION_CLOSED);
        }
    }
}
