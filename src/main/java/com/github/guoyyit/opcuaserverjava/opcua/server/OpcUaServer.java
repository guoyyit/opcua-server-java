package com.github.guoyyit.opcuaserverjava.opcua.server;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.AddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.channel.SecureChannel;
import com.github.guoyyit.opcuaserverjava.opcua.channel.SecureChannelService;
import com.github.guoyyit.opcuaserverjava.opcua.core.AccessLevel;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseDirection;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseResult;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.PublishResult;
import com.github.guoyyit.opcuaserverjava.opcua.core.UserIdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.security.UserIdentity;
import com.github.guoyyit.opcuaserverjava.opcua.session.Session;
import com.github.guoyyit.opcuaserverjava.opcua.session.SessionService;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.MonitoredItem;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.Subscription;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.SubscriptionService;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * OPC UA 核心服务集门面：串联通道、会话、地址空间、读写、订阅与发布。
 * 所有业务请求统一经过“通道校验 → 会话校验 → 业务处理”，
 * 进程内本地回环直接调用，不依赖任何外部服务。
 */
public class OpcUaServer {

    private final AddressSpace addressSpace;
    private final SecureChannelService secureChannelService;
    private final SessionService sessionService;
    private final SubscriptionService subscriptionService;
    private final LongSupplier clock;
    private ScheduledExecutorService reaper;

    public OpcUaServer(AddressSpace addressSpace,
                       SecureChannelService secureChannelService,
                       SessionService sessionService,
                       SubscriptionService subscriptionService,
                       LongSupplier clock) {
        this.addressSpace = addressSpace;
        this.secureChannelService = secureChannelService;
        this.sessionService = sessionService;
        this.subscriptionService = subscriptionService;
        this.clock = clock;

        // 会话失效（关闭/超时）→ 回收其全部订阅。
        this.sessionService.setOnSessionInvalid(session ->
                subscriptionService.deleteAllSubscriptionsOfSession(session.getSessionId()));
        // 通道关闭/超时 → 回收该通道上的全部会话，进而回收订阅。
        this.secureChannelService.setOnChannelClosed(channel -> {
            sessionService.closeAllSessionsOnChannel(channel.getChannelId());
        });
    }

    public AddressSpace getAddressSpace() {
        return addressSpace;
    }

    public SecureChannelService getSecureChannelService() {
        return secureChannelService;
    }

    public SessionService getSessionService() {
        return sessionService;
    }

    public SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    public SecureChannel openChannel(long requestedLifetimeMillis) {
        return secureChannelService.openChannel(requestedLifetimeMillis);
    }

    public void closeChannel(long channelId) {
        secureChannelService.closeChannel(channelId);
    }

    public long nextRequestSequence(SecureChannel channel) {
        return channel.getExpectedRequestSequence();
    }

    public Session createSession(long channelId, long requestSequence, long requestedTimeoutMillis) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        return sessionService.createSession(channel, requestedTimeoutMillis);
    }

    public Session activateSession(long channelId, long requestSequence,
                                   long sessionId, UserIdentityToken token) {
        secureChannelService.validateRequest(channelId, requestSequence);
        return sessionService.activateSession(sessionId, token);
    }

    public void closeSession(long channelId, long requestSequence, long sessionId) {
        secureChannelService.validateRequest(channelId, requestSequence);
        sessionService.closeSession(sessionId);
    }

    public BrowseResult browse(long channelId, long requestSequence, long sessionId,
                               NodeId nodeId, BrowseDirection direction,
                               String referenceTypeId, int maxReferencesPerNode,
                               byte[] continuationPoint) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        sessionService.requireUsableSession(sessionId, channel);
        return addressSpace.browse(nodeId, direction, referenceTypeId,
                maxReferencesPerNode, continuationPoint);
    }

    public DataValue read(long channelId, long requestSequence,
                          long sessionId, NodeId nodeId) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        Session session = sessionService.requireUsableSession(sessionId, channel);
        DataValue value = addressSpace.read(nodeId);
        if (!hasReadAccess(session.getIdentity())) {
            throw new OpcUaException(StatusCode.BAD_USER_ACCESS_DENIED);
        }
        return value;
    }

    public void write(long channelId, long requestSequence,
                      long sessionId, NodeId nodeId, Object value) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        Session session = sessionService.requireUsableSession(sessionId, channel);
        if (!hasWriteAccess(session.getIdentity())) {
            throw new OpcUaException(StatusCode.BAD_USER_ACCESS_DENIED);
        }
        addressSpace.write(nodeId, value);
    }

    public Subscription createSubscription(long channelId, long requestSequence,
                                           long sessionId,
                                           double publishingIntervalMillis,
                                           long retentionWindowMillis) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        Session session = sessionService.requireUsableSession(sessionId, channel);
        Subscription subscription = subscriptionService.createSubscription(
                session.getSessionId(), publishingIntervalMillis, retentionWindowMillis);
        session.getSubscriptionIds().add(subscription.getSubscriptionId());
        return subscription;
    }

    public MonitoredItem createMonitoredItem(long channelId, long requestSequence,
                                             long sessionId, long subscriptionId,
                                             NodeId nodeId, long clientHandle,
                                             MonitoringParameters parameters) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        Session session = sessionService.requireUsableSession(sessionId, channel);
        return subscriptionService.createMonitoredItem(session.getSessionId(),
                subscriptionId, nodeId, clientHandle, parameters);
    }

    public void deleteMonitoredItem(long channelId, long requestSequence,
                                    long sessionId, long subscriptionId,
                                    long monitoredItemId) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        Session session = sessionService.requireUsableSession(sessionId, channel);
        subscriptionService.deleteMonitoredItem(session.getSessionId(),
                subscriptionId, monitoredItemId);
    }

    public void deleteSubscription(long channelId, long requestSequence,
                                   long sessionId, long subscriptionId) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        Session session = sessionService.requireUsableSession(sessionId, channel);
        subscriptionService.deleteSubscription(session.getSessionId(), subscriptionId);
        session.getSubscriptionIds().remove(subscriptionId);
    }

    public Subscription transferSubscription(long channelId, long requestSequence,
                                             long oldSessionId, long newSessionId,
                                             long subscriptionId) {
        // 迁移请求来自新通道：新会话必须在当前通道上且可用。
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        sessionService.requireUsableSession(newSessionId, channel);
        Subscription transferred = subscriptionService.transferSubscription(
                oldSessionId, newSessionId, subscriptionId);
        return transferred;
    }

    public PublishResult publish(long channelId, long requestSequence,
                                 long sessionId, long subscriptionId,
                                 long timeoutMillis, List<Long> acknowledgements) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        Session session = sessionService.requireUsableSession(sessionId, channel);
        return subscriptionService.publish(session.getSessionId(), subscriptionId,
                timeoutMillis, acknowledgements);
    }

    public PublishResult republish(long channelId, long requestSequence,
                                   long sessionId, long subscriptionId,
                                   long sequenceNumber) {
        SecureChannel channel = secureChannelService.validateRequest(channelId, requestSequence);
        Session session = sessionService.requireUsableSession(sessionId, channel);
        return subscriptionService.republish(session.getSessionId(),
                subscriptionId, sequenceNumber);
    }

    public void start() {
        subscriptionService.start(5L);
    }

    public void stop() {
        subscriptionService.shutdown();
        if (reaper != null) {
            reaper.shutdownNow();
        }
    }

    /**
     * 启动后台清扫：周期性检测通道与会话超时。
     */
    public void startReaper(long intervalMillis) {
        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "opcua-session-reaper");
            thread.setDaemon(true);
            return thread;
        });
        reaper.scheduleAtFixedRate(() -> {
            long now = clock.getAsLong();
            secureChannelService.reapExpiredChannels(now);
            sessionService.reapExpiredSessions(now);
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private boolean hasReadAccess(UserIdentity identity) {
        // 匿名与已认证用户均可读。
        return identity != null;
    }

    private boolean hasWriteAccess(UserIdentity identity) {
        // 写权限：非匿名用户（凭据激活）方可写。
        return identity != null && identity.canWrite();
    }
}
