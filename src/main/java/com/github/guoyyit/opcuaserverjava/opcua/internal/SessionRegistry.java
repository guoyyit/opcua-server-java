package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaProperties;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaStats;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry, lifecycle and idle reaper for sessions.
 */
@Component
public class SessionRegistry {

    private final OpcUaProperties properties;
    private final OpcUaStats stats;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile SessionCloseListener closeListener = session -> {
    };

    public SessionRegistry(OpcUaProperties properties, OpcUaStats stats) {
        this.properties = properties;
        this.stats = stats;
    }

    public void setCloseListener(SessionCloseListener closeListener) {
        this.closeListener = closeListener;
    }

    public Session create(int channelId, long requestedTimeoutMs) {
        if (sessions.size() >= properties.getMaxSessions()) {
            throw new OpcUaException(StatusCode.BAD_TOO_MANY_SESSIONS);
        }
        long timeoutMs = requestedTimeoutMs <= 0
                ? properties.getDefaultSessionTimeoutMs()
                : Math.min(requestedTimeoutMs, properties.getDefaultSessionTimeoutMs() * 10);
        String token = UUID.randomUUID().toString();
        Session session = new Session(token, channelId, timeoutMs);
        sessions.put(token, session);
        stats.sessionIncrement();
        return session;
    }

    /**
     * Returns a live session. Expired sessions are closed on access and the
     * caller receives a distinguishable invalid-session error.
     */
    public Session require(String token) {
        Session session = sessions.get(token);
        if (session == null) {
            throw new OpcUaException(StatusCode.BAD_SESSION_ID_INVALID);
        }
        if (session.closed()) {
            sessions.remove(token);
            throw new OpcUaException(StatusCode.BAD_SESSION_CLOSED);
        }
        if (session.expired(Instant.now())) {
            closeSession(session);
            throw new OpcUaException(StatusCode.BAD_SESSION_CLOSED);
        }
        return session;
    }

    public int size() {
        return sessions.size();
    }

    public void closeSession(Session session) {
        if (session == null) {
            return;
        }
        if (session.closed()) {
            return;
        }
        session.close();
        sessions.remove(session.token());
        stats.sessionDecrement();
        closeListener.onClosed(session);
    }

    public void closeByChannel(int channelId) {
        for (Session session : sessions.values()) {
            if (session.channelId() == channelId) {
                closeSession(session);
            }
        }
    }

    public void reapExpired() {
        Instant now = Instant.now();
        for (Session session : sessions.values()) {
            if (!session.closed() && session.expired(now)) {
                closeSession(session);
            }
        }
    }

    @FunctionalInterface
    public interface SessionCloseListener {
        void onClosed(Session session);
    }
}
