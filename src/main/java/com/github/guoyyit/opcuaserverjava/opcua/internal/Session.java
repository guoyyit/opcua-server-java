package com.github.guoyyit.opcuaserverjava.opcua.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server side session. A session is unusable until activated and becomes
 * invalid after its idle timeout. Closing it releases all subscriptions.
 */
public final class Session {

    private final String token;
    private final int channelId;
    private final long timeoutMs;
    private volatile Instant lastActivity;
    private final AtomicBoolean activated = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String policyId;
    private volatile Set<String> roles = Set.of();

    Session(String token, int channelId, long timeoutMs) {
        this.token = token;
        this.channelId = channelId;
        this.timeoutMs = timeoutMs;
        this.lastActivity = Instant.now();
    }

    public String token() {
        return token;
    }

    public int channelId() {
        return channelId;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    public void touch() {
        this.lastActivity = Instant.now();
    }

    public boolean expired(Instant now) {
        return Duration.between(lastActivity, now).toMillis() >= timeoutMs;
    }

    public boolean activated() {
        return activated.get();
    }

    public void activate(String policyId, Set<String> roles) {
        this.policyId = policyId;
        this.roles = Set.copyOf(roles);
        activated.set(true);
        touch();
    }

    public String policyId() {
        return policyId;
    }

    public Set<String> roles() {
        return Collections.unmodifiableSet(roles);
    }

    public boolean closed() {
        return closed.get();
    }

    public void close() {
        closed.set(true);
    }
}
