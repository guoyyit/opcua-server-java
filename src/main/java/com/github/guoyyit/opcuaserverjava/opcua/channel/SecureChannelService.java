package com.github.guoyyit.opcuaserverjava.opcua.channel;

import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 通道生命周期管理：创建、请求校验（状态+序列号+超时）、关闭、超时清扫。
 */
public class SecureChannelService {

    private final Map<Long, SecureChannel> channels = new ConcurrentHashMap<>();
    private final AtomicLong channelIdSequence = new AtomicLong(0L);
    private final LongSupplier clock;
    private final long maxLifetimeMillis;
    private Consumer<SecureChannel> onChannelClosed;

    public SecureChannelService(LongSupplier clock, long maxLifetimeMillis) {
        this.clock = clock;
        this.maxLifetimeMillis = maxLifetimeMillis;
    }

    public void setOnChannelClosed(Consumer<SecureChannel> onChannelClosed) {
        this.onChannelClosed = onChannelClosed;
    }

    public SecureChannel openChannel(long requestedLifetimeMillis) {
        long lifetime = Math.max(1L, Math.min(requestedLifetimeMillis, maxLifetimeMillis));
        long channelId = channelIdSequence.incrementAndGet();
        SecureChannel channel = new SecureChannel(channelId, lifetime, clock.getAsLong());
        channels.put(channelId, channel);
        return channel;
    }

    /**
     * 校验通道存在、未关闭、未超时，请求序列号严格等于期望值。
     * 超时转换为关闭并触发级联回收回调。
     */
    public SecureChannel validateRequest(long channelId, long requestSequenceNumber) {
        long now = clock.getAsLong();
        SecureChannel channel = channels.get(channelId);
        if (channel == null) {
            throw new OpcUaException(StatusCode.BAD_SECURE_CHANNEL_ID_INVALID);
        }
        if (channel.isExpired(now)) {
            doClose(channel, StatusCode.BAD_SECURE_CHANNEL_TIMEOUT);
            throw new OpcUaException(StatusCode.BAD_SECURE_CHANNEL_TIMEOUT);
        }
        if (channel.isClosed()) {
            throw new OpcUaException(StatusCode.BAD_SECURE_CHANNEL_CLOSED);
        }
        if (requestSequenceNumber != channel.getExpectedRequestSequence()) {
            throw new OpcUaException(StatusCode.BAD_SEQUENCE_NUMBER_INVALID);
        }
        channel.advanceRequestSequence();
        channel.touch(now);
        return channel;
    }

    public long nextResponseSequence(SecureChannel channel) {
        return channel.consumeResponseSequence();
    }

    public void closeChannel(long channelId) {
        SecureChannel channel = channels.get(channelId);
        if (channel == null) {
            throw new OpcUaException(StatusCode.BAD_SECURE_CHANNEL_ID_INVALID);
        }
        doClose(channel, StatusCode.BAD_SECURE_CHANNEL_CLOSED);
    }

    public int reapExpiredChannels(long nowMillis) {
        int reaped = 0;
        Iterator<Map.Entry<Long, SecureChannel>> iterator = channels.entrySet().iterator();
        while (iterator.hasNext()) {
            SecureChannel channel = iterator.next().getValue();
            if (channel.isExpired(nowMillis)) {
                doClose(channel, StatusCode.BAD_SECURE_CHANNEL_TIMEOUT);
                reaped++;
            }
        }
        return reaped;
    }

    public int activeChannelCount() {
        return (int) channels.values().stream().filter(c -> !c.isClosed()).count();
    }

    private void doClose(SecureChannel channel, StatusCode reason) {
        if (channel.isClosed()) {
            return;
        }
        channel.close();
        channels.remove(channel.getChannelId());
        if (onChannelClosed != null) {
            onChannelClosed.accept(channel);
        }
    }
}
