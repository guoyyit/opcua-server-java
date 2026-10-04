package com.github.guoyyit.opcuaserverjava.opcua.client;

import com.github.guoyyit.opcuaserverjava.opcua.channel.SecureChannel;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseDirection;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseResult;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.core.PublishResult;
import com.github.guoyyit.opcuaserverjava.opcua.core.UserIdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.server.OpcUaServer;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.MonitoredItem;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.Subscription;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地回环客户端：在进程内直接调用 {@link OpcUaServer}，
 * 严格维护本通道独立、严格递增的请求序列号（1,2,3...）。
 */
public class LoopbackClient implements AutoCloseable {

    private final OpcUaServer server;
    private SecureChannel channel;
    private long sessionId;
    private final AtomicLong clientSequence = new AtomicLong(1L);

    public LoopbackClient(OpcUaServer server) {
        this.server = server;
    }

    public SecureChannel openChannel(long lifetimeMillis) {
        this.channel = server.openChannel(lifetimeMillis);
        this.clientSequence.set(1L);
        return channel;
    }

    public long createSession(long timeoutMillis) {
        requireChannel();
        var session = server.createSession(channel.getChannelId(),
                clientSequence.getAndIncrement(), timeoutMillis);
        this.sessionId = session.getSessionId();
        return sessionId;
    }

    public long createSession() {
        return createSession(30_000L);
    }

    public void activateAnonymous() {
        requireChannel();
        server.activateSession(channel.getChannelId(), clientSequence.getAndIncrement(),
                sessionId, UserIdentityToken.anonymous());
    }

    public void activateUser(String userName, String password) {
        requireChannel();
        server.activateSession(channel.getChannelId(), clientSequence.getAndIncrement(),
                sessionId, UserIdentityToken.username(userName, password));
    }

    public void closeSession() {
        if (channel != null && sessionId != 0L) {
            server.closeSession(channel.getChannelId(),
                    clientSequence.getAndIncrement(), sessionId);
            sessionId = 0L;
        }
    }

    public void closeChannel() {
        if (channel != null) {
            server.closeChannel(channel.getChannelId());
            channel = null;
            sessionId = 0L;
        }
    }

    public BrowseResult browse(NodeId nodeId, BrowseDirection direction,
                               String referenceTypeId, int maxPerNode) {
        requireChannel();
        return server.browse(channel.getChannelId(), clientSequence.getAndIncrement(),
                sessionId, nodeId, direction, referenceTypeId, maxPerNode, new byte[0]);
    }

    public BrowseResult browseNext(byte[] continuationPoint) {
        requireChannel();
        return server.browse(channel.getChannelId(), clientSequence.getAndIncrement(),
                sessionId, NodeId.of("unused"), BrowseDirection.FORWARD,
                null, 100, continuationPoint);
    }

    public DataValue read(NodeId nodeId) {
        requireChannel();
        return server.read(channel.getChannelId(), clientSequence.getAndIncrement(),
                sessionId, nodeId);
    }

    public void write(NodeId nodeId, Object value) {
        requireChannel();
        server.write(channel.getChannelId(), clientSequence.getAndIncrement(),
                sessionId, nodeId, value);
    }

    public Subscription createSubscription(double publishingIntervalMillis,
                                           long retentionWindowMillis) {
        requireChannel();
        return server.createSubscription(channel.getChannelId(),
                clientSequence.getAndIncrement(), sessionId,
                publishingIntervalMillis, retentionWindowMillis);
    }

    public MonitoredItem createMonitoredItem(long subscriptionId, NodeId nodeId,
                                             long clientHandle,
                                             MonitoringParameters parameters) {
        requireChannel();
        return server.createMonitoredItem(channel.getChannelId(),
                clientSequence.getAndIncrement(), sessionId, subscriptionId,
                nodeId, clientHandle, parameters);
    }

    public void deleteSubscription(long subscriptionId) {
        requireChannel();
        server.deleteSubscription(channel.getChannelId(),
                clientSequence.getAndIncrement(), sessionId, subscriptionId);
    }

    public Subscription transferSubscription(long oldSessionId, long newSessionId,
                                             long subscriptionId) {
        requireChannel();
        return server.transferSubscription(channel.getChannelId(),
                clientSequence.getAndIncrement(), oldSessionId, newSessionId,
                subscriptionId);
    }

    public PublishResult publish(long subscriptionId, long timeoutMillis,
                                 List<Long> acknowledgements) {
        requireChannel();
        return server.publish(channel.getChannelId(), clientSequence.getAndIncrement(),
                sessionId, subscriptionId, timeoutMillis, acknowledgements);
    }

    public PublishResult republish(long subscriptionId, long sequenceNumber) {
        requireChannel();
        return server.republish(channel.getChannelId(), clientSequence.getAndIncrement(),
                sessionId, subscriptionId, sequenceNumber);
    }

    public long getChannelId() {
        return channel == null ? 0L : channel.getChannelId();
    }

    public long getSessionId() {
        return sessionId;
    }

    @Override
    public void close() {
        try {
            closeSession();
        } finally {
            closeChannel();
        }
    }

    private void requireChannel() {
        if (channel == null) {
            throw new IllegalStateException("通道尚未建立");
        }
    }
}
