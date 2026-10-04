package com.github.guoyyit.opcuaserverjava.opcua.internal;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server side secure channel. Owns independent request/response sequences and
 * a lifetime. Timed out channels are closed and reject every later request.
 */
public final class SecureChannel {

    private final int channelId;
    private final Socket socket;
    private volatile long lifetimeMs;
    private volatile Instant lastActivity;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean closePending = new AtomicBoolean(false);
    private final AtomicLong expectedRequestSequence = new AtomicLong(1);
    private final AtomicLong nextResponseSequence = new AtomicLong(1);

    SecureChannel(int channelId, Socket socket, long lifetimeMs) {
        this.channelId = channelId;
        this.socket = socket;
        this.lifetimeMs = lifetimeMs;
        this.lastActivity = Instant.now();
    }

    public int channelId() {
        return channelId;
    }

    public Socket socket() {
        return socket;
    }

    public long lifetimeMs() {
        return lifetimeMs;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    public void touch() {
        this.lastActivity = Instant.now();
    }

    public void renewLifetime(long lifetimeMs) {
        this.lifetimeMs = lifetimeMs;
        touch();
    }

    /**
     * Each channel has its own strictly increasing request sequence. The client
     * serializes writes, so arrival order equals sequence order. A mismatch
     * closes the channel (OPC UA transport semantics).
     */
    public boolean isExpectedRequestSequence(long sequenceNumber) {
        return sequenceNumber == expectedRequestSequence.get();
    }

    public void advanceExpectedRequestSequence() {
        expectedRequestSequence.incrementAndGet();
    }

    public long takeResponseSequence() {
        return nextResponseSequence.getAndIncrement();
    }

    public boolean expired(Instant now) {
        return Duration.between(lastActivity, now).toMillis() >= lifetimeMs;
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // socket cleanup best effort
            }
        }
    }

    public boolean closed() {
        return closed.get();
    }

    /**
     * Marks the channel for close after the in-flight response has been sent.
     * The transport loop exits after writing the response.
     */
    public void markClosePending() {
        closePending.set(true);
    }

    public boolean closePending() {
        return closePending.get();
    }

}
