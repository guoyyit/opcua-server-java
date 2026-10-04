package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaProperties;
import com.github.guoyyit.opcuaserverjava.opcua.OpcUaStats;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import org.springframework.stereotype.Component;

import java.net.Socket;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry and timeout reaper for secure channels. When a channel is closed
 * (timeout or explicit close) every session bound to it and those sessions'
 * subscriptions are released, so nothing keeps occupying resources.
 */
@Component
public class ChannelRegistry {

    private final OpcUaProperties properties;
    private final OpcUaStats stats;
    private final Map<Integer, SecureChannel> channels = new ConcurrentHashMap<>();
    private final AtomicInteger idGenerator = new AtomicInteger(0);
    private volatile ChannelCloseListener closeListener = channel -> {
    };

    public ChannelRegistry(OpcUaProperties properties, OpcUaStats stats) {
        this.properties = properties;
        this.stats = stats;
    }

    public void setCloseListener(ChannelCloseListener closeListener) {
        this.closeListener = closeListener;
    }

    public SecureChannel register(Socket socket, long requestedLifetimeMs) {
        int channelId = idGenerator.incrementAndGet();
        long lifetimeMs = requestedLifetimeMs <= 0
                ? properties.getDefaultChannelLifetimeMs()
                : Math.min(requestedLifetimeMs, properties.getDefaultChannelLifetimeMs() * 10);
        SecureChannel channel = new SecureChannel(channelId, socket, lifetimeMs);
        channels.put(channelId, channel);
        stats.channelIncrement();
        return channel;
    }

    public SecureChannel require(int channelId) {
        SecureChannel channel = channels.get(channelId);
        if (channel == null || channel.closed()) {
            throw new OpcUaException(StatusCode.BAD_SECURE_CHANNEL_ID_TOKEN_INVALID);
        }
        return channel;
    }

    public int size() {
        return channels.size();
    }

    /**
     * Closes a channel and releases its sessions/subscriptions. Idempotent.
     */
    public void closeChannel(SecureChannel channel) {
        if (channel == null) {
            return;
        }
        if (channel.closed()) {
            return;
        }
        channel.close();
        channels.remove(channel.channelId());
        stats.channelDecrement();
        closeListener.onClosed(channel);
    }

    public void closeChannel(int channelId) {
        closeChannel(channels.get(channelId));
    }

    public void reapExpired() {
        Instant now = Instant.now();
        for (SecureChannel channel : channels.values()) {
            if (!channel.closed() && channel.expired(now)) {
                closeChannel(channel);
            }
        }
    }

    @FunctionalInterface
    public interface ChannelCloseListener {
        void onClosed(SecureChannel channel);
    }
}
