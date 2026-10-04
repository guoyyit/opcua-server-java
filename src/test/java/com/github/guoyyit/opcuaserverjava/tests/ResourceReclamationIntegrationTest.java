package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.client.LoopbackClient;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 端到端资源回收：取消发布（删除订阅）、关闭会话、关闭通道三级回收，
 * 以及重连迁移后旧会话关闭不影响迁移走的订阅。
 */
class ResourceReclamationIntegrationTest {

    private final TestServerFactory.TestContext context = TestServerFactory.newContext();

    private LoopbackClient operator() {
        LoopbackClient client = new LoopbackClient(context.server);
        client.openChannel(10_000L);
        client.createSession(5_000L);
        client.activateUser("operator", "operator-pass");
        return client;
    }

    @Test
    void fullLifecycleReclaimsAllResources() {
        assertEquals(0, context.subscriptionService.activeSubscriptionCount());

        try (LoopbackClient client = operator()) {
            long first = client.createSubscription(10.0d, 60_000L).getSubscriptionId();
            long second = client.createSubscription(10.0d, 60_000L).getSubscriptionId();
            client.createMonitoredItem(first, DemoAddressSpace.SET_POINT, 1L,
                    new MonitoringParameters(5.0d, 10,
                            MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
            client.createMonitoredItem(second, DemoAddressSpace.TEMPERATURE, 2L,
                    new MonitoringParameters(5.0d, 10,
                            MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
            assertEquals(2, context.subscriptionService.activeSubscriptionCount());

            // 客户端取消发布 → 对应订阅资源立即释放，其余订阅不受影响。
            client.deleteSubscription(first);
            assertEquals(1, context.subscriptionService.activeSubscriptionCount());

            // close() 关闭会话与通道：剩余订阅被级联回收。
        }
        assertEquals(0, context.sessionService.activeSessionCount());
        assertEquals(0, context.channelService.activeChannelCount());
        assertEquals(0, context.subscriptionService.activeSubscriptionCount());
    }

    @Test
    void transferredSubscriptionSurvivesOldSessionCloseAndIsReclaimedAtEnd() {
        var oldClient = operator();
        long oldSessionId = oldClient.getSessionId();
        long subscriptionId = oldClient.createSubscription(10.0d, 60_000L).getSubscriptionId();
        oldClient.createMonitoredItem(subscriptionId, DemoAddressSpace.SET_POINT, 1L,
                new MonitoringParameters(5.0d, 10,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));

        oldClient.write(DemoAddressSpace.SET_POINT, 77);
        context.tickOnly(10L);
        oldClient.publish(subscriptionId, 50L, List.of());

        var newClient = operator();
        long newSessionId = newClient.getSessionId();
        newClient.transferSubscription(oldSessionId, newSessionId, subscriptionId);

        // 关闭旧会话：迁移走的订阅不能被旧会话的清理动作回收。
        oldClient.closeSession();
        assertEquals(1, context.subscriptionService.activeSubscriptionCount());

        // 新会话仍可重发漏收的序列号。
        assertEquals(1L,
                newClient.republish(subscriptionId, 1L).getMessage().getSequenceNumber());

        // 新会话关闭（或新通道关闭）后订阅最终被回收。
        newClient.close();
        assertEquals(0, context.subscriptionService.activeSubscriptionCount());
        assertEquals(0, context.sessionService.activeSessionCount());
    }
}
