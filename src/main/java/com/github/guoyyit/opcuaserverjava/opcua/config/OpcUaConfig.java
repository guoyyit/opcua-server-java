package com.github.guoyyit.opcuaserverjava.opcua.config;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.AddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.channel.SecureChannelService;
import com.github.guoyyit.opcuaserverjava.opcua.security.IdentityValidator;
import com.github.guoyyit.opcuaserverjava.opcua.security.SimpleIdentityValidator;
import com.github.guoyyit.opcuaserverjava.opcua.server.OpcUaServer;
import com.github.guoyyit.opcuaserverjava.opcua.session.SessionService;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.SubscriptionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.LongSupplier;

/**
 * OPC UA 核心服务装配。全部组件均为内存实现，无外部依赖。
 */
@Configuration
@EnableConfigurationProperties(OpcUaProperties.class)
public class OpcUaConfig {

    private static final long MAX_CHANNEL_LIFETIME_MILLIS = 600_000L;
    private static final long MAX_SESSION_TIMEOUT_MILLIS = 600_000L;
    private static final long MAX_RETAINED_MESSAGES = 1_000L;

    @Bean
    public AddressSpace addressSpace() {
        return DemoAddressSpace.create();
    }

    @Bean
    public IdentityValidator identityValidator() {
        return new SimpleIdentityValidator();
    }

    @Bean
    public SecureChannelService secureChannelService(OpcUaProperties properties) {
        LongSupplier clock = System::currentTimeMillis;
        return new SecureChannelService(clock, MAX_CHANNEL_LIFETIME_MILLIS);
    }

    @Bean
    public SessionService sessionService(IdentityValidator identityValidator,
                                         OpcUaProperties properties) {
        LongSupplier clock = System::currentTimeMillis;
        return new SessionService(identityValidator, clock,
                MAX_SESSION_TIMEOUT_MILLIS, properties.getMaxSessions());
    }

    @Bean
    public SubscriptionService subscriptionService(AddressSpace addressSpace,
                                                   OpcUaProperties properties) {
        LongSupplier clock = System::currentTimeMillis;
        SubscriptionService service = new SubscriptionService(addressSpace, clock,
                properties.getMaxMonitoredItemsPerSession(),
                properties.getMaxNotificationQueueDepthPerSession(),
                MAX_RETAINED_MESSAGES);
        service.start((long) properties.getSchedulerTickMillis());
        return service;
    }

    @Bean
    public OpcUaServer opcUaServer(AddressSpace addressSpace,
                                   SecureChannelService secureChannelService,
                                   SessionService sessionService,
                                   SubscriptionService subscriptionService,
                                   OpcUaProperties properties) {
        OpcUaServer server = new OpcUaServer(addressSpace, secureChannelService,
                sessionService, subscriptionService, System::currentTimeMillis);
        server.startReaper(properties.getReaperIntervalMillis());
        return server;
    }
}
