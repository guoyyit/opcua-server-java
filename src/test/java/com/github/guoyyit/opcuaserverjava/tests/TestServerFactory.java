package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.AddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.channel.SecureChannelService;
import com.github.guoyyit.opcuaserverjava.opcua.security.IdentityValidator;
import com.github.guoyyit.opcuaserverjava.opcua.security.SimpleIdentityValidator;
import com.github.guoyyit.opcuaserverjava.opcua.server.OpcUaServer;
import com.github.guoyyit.opcuaserverjava.opcua.session.SessionService;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.SubscriptionService;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 测试辅助：用可控虚拟时钟手工装配服务端，不启动后台调度线程，
 * 采样/打包/超时全部显式驱动，保证测试确定性。
 */
final class TestServerFactory {

    static final long MAX_CHANNEL_LIFETIME = 600_000L;
    static final long MAX_SESSION_TIMEOUT = 600_000L;
    static final long MAX_RETAINED_MESSAGES = 1_000L;

    private TestServerFactory() {
    }

    static final class TestContext {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AddressSpace addressSpace;
        final SecureChannelService channelService;
        final SessionService sessionService;
        final SubscriptionService subscriptionService;
        final OpcUaServer server;

        private TestContext(int maxSessions, int maxMonitoredItems, int maxQueueDepth) {
            LongSupplier clockSupplier = clock::get;
            this.addressSpace = DemoAddressSpace.create();
            IdentityValidator identityValidator = new SimpleIdentityValidator();
            this.channelService =
                    new SecureChannelService(clockSupplier, MAX_CHANNEL_LIFETIME);
            this.sessionService = new SessionService(identityValidator, clockSupplier,
                    MAX_SESSION_TIMEOUT, maxSessions);
            this.subscriptionService = new SubscriptionService(addressSpace, clockSupplier,
                    maxMonitoredItems, maxQueueDepth, MAX_RETAINED_MESSAGES);
            this.server = new OpcUaServer(addressSpace, channelService,
                    sessionService, subscriptionService, clock::get);
        }

        /** 推进时钟：触发超时清扫 + 一次订阅采样/打包。 */
        void advance(long millis) {
            long now = clock.addAndGet(millis);
            channelService.reapExpiredChannels(now);
            sessionService.reapExpiredSessions(now);
            subscriptionService.tick(now);
        }

        /** 仅驱动订阅采样/打包，不做超时清扫。 */
        void tickOnly(long millis) {
            subscriptionService.tick(clock.addAndGet(millis));
        }

        /** 推进时钟并清扫会话/驱动订阅，但不主动清扫通道，
         * 用于验证“请求到达时才判定通道超时”。 */
        void advanceWithoutChannelReap(long millis) {
            long now = clock.addAndGet(millis);
            sessionService.reapExpiredSessions(now);
            subscriptionService.tick(now);
        }
    }

    static TestContext newContext() {
        return new TestContext(100, 200, 2_000);
    }

    static TestContext newLimitedContext(int maxSessions,
                                         int maxMonitoredItems,
                                         int maxQueueDepth) {
        return new TestContext(maxSessions, maxMonitoredItems, maxQueueDepth);
    }
}
