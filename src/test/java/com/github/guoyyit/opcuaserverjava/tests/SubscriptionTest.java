package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.client.LoopbackClient;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import com.github.guoyyit.opcuaserverjava.opcua.core.PublishResult;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.MonitoredItem;
import com.github.guoyyit.opcuaserverjava.opcua.subscription.Subscription;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订阅/监控项：采样参数、序列号连续、采样发布错位不乱序不丢号、丢弃策略。
 */
class SubscriptionTest {

    private final TestServerFactory.TestContext context = TestServerFactory.newContext();

    private LoopbackClient activatedOperator() {
        LoopbackClient client = new LoopbackClient(context.server);
        client.openChannel(10_000L);
        client.createSession(5_000L);
        client.activateUser("operator", "operator-pass");
        return client;
    }

    @Test
    void monitoredItemDeliversDataChangeNotifications() {
        var client = activatedOperator();
        Subscription subscription = client.createSubscription(10.0d, 60_000L);
        long subscriptionId = subscription.getSubscriptionId();
        MonitoredItem item = client.createMonitoredItem(subscriptionId,
                DemoAddressSpace.SET_POINT, 7L,
                new MonitoringParameters(5.0d, 10,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
        assertEquals(7L, item.getClientHandle());

        // 首个采样周期产生初始通知。
        context.tickOnly(10L);
        PublishResult first = client.publish(subscriptionId, 1L, List.of());
        assertFalse(first.isKeepAlive());
        assertEquals(1L, first.getMessage().getSequenceNumber());
        assertEquals(25, first.getMessage().getNotifications().get(0).value().getValue());

        // 无变化时为 keep-alive，不占序列号。
        context.tickOnly(10L);
        PublishResult idle = client.publish(subscriptionId, 1L, List.of());
        assertTrue(idle.isKeepAlive());

        // 值变化后得到新通知，序列号继续为 2。
        client.write(DemoAddressSpace.SET_POINT, 26);
        context.tickOnly(10L);
        PublishResult second = client.publish(subscriptionId, 1L, List.of());
        assertEquals(2L, second.getMessage().getSequenceNumber());
        assertEquals(26, second.getMessage().getNotifications().get(0).value().getValue());
        client.close();
    }

    @Test
    void sequenceNumbersAreMonotonicAcrossStaggeredSampling() {
        var client = activatedOperator();
        long subscriptionId = client.createSubscription(10.0d, 60_000L).getSubscriptionId();

        // 两个监控项采样间隔不同（5ms/15ms），与 10ms 发布周期错位。
        client.createMonitoredItem(subscriptionId, DemoAddressSpace.SET_POINT, 1L,
                new MonitoringParameters(5.0d, 50,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
        client.createMonitoredItem(subscriptionId, DemoAddressSpace.TEMPERATURE, 2L,
                new MonitoringParameters(15.0d, 50,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));

        context.tickOnly(10L);
        client.publish(subscriptionId, 1L, List.of()); // seq 1

        List<Long> observed = new ArrayList<>();
        observed.add(1L);
        for (int tick = 1; tick <= 10; tick++) {
            // 每个周期修改两个变量，保证总有数据变化。
            client.write(DemoAddressSpace.SET_POINT, 100 + tick);
            client.write(DemoAddressSpace.TEMPERATURE, 20.0d + tick);
            context.tickOnly(10L);
            PublishResult result = client.publish(subscriptionId, 1L, List.of());
            if (!result.isKeepAlive()) {
                observed.add(result.getMessage().getSequenceNumber());
            }
        }

        // 序列号严格单调递增、连续不缺号。
        for (int i = 1; i < observed.size(); i++) {
            assertEquals(observed.get(i - 1) + 1, observed.get(i),
                    "序列号出现乱序或丢号");
        }
        assertTrue(observed.size() >= 5);
        client.close();
    }

    @Test
    void discardOldestAndNewestPoliciesBehaveDifferently() {
        // 队列大小 2、采样 1ms、发布 100ms：一个发布周期内多次变化，
        // 验证两种丢弃策略在同场景下表现不同。
        var client = activatedOperator();
        long subscriptionId = client.createSubscription(100.0d, 60_000L).getSubscriptionId();
        MonitoredItem item = client.createMonitoredItem(subscriptionId,
                DemoAddressSpace.SET_POINT, 1L,
                new MonitoringParameters(1.0d, 2,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
        assertEquals(2, item.getParameters().getQueueSize());

        for (int i = 0; i < 5; i++) {
            client.write(DemoAddressSpace.SET_POINT, 100 + i);
            context.tickOnly(1L);
        }
        context.tickOnly(100L);
        PublishResult result = client.publish(subscriptionId, 1L, List.of());
        var notifications = result.getMessage().getNotifications();
        // DISCARD_OLDEST：最终一定能看到最后写入的值 104。
        Object last = notifications.get(notifications.size() - 1).value().getValue();
        assertEquals(104, last);

        long newestSubscriptionId = client.createSubscription(100.0d, 60_000L).getSubscriptionId();
        client.createMonitoredItem(newestSubscriptionId, DemoAddressSpace.TEMPERATURE, 2L,
                new MonitoringParameters(1.0d, 2,
                        MonitoringParameters.DiscardPolicy.DISCARD_NEWEST));
        for (int i = 0; i < 5; i++) {
            client.write(DemoAddressSpace.TEMPERATURE, 40.0d + i);
            context.tickOnly(1L);
        }
        context.tickOnly(100L);
        PublishResult newestResult = client.publish(newestSubscriptionId, 1L, List.of());
        var newestNotifications = newestResult.getMessage().getNotifications();
        // DISCARD_NEWEST：保留最旧两个采样（40.0、41.0），最新的 44.0 被丢弃。
        assertEquals(2, newestNotifications.size());
        assertEquals(40.0d, newestNotifications.get(0).value().getValue());
        assertEquals(41.0d, newestNotifications.get(1).value().getValue());
        client.close();
    }

    @Test
    void deletingSubscriptionReleasesResources() {
        var client = activatedOperator();
        long subscriptionId = client.createSubscription(10.0d, 60_000L).getSubscriptionId();
        client.createMonitoredItem(subscriptionId, DemoAddressSpace.SET_POINT, 1L,
                new MonitoringParameters(5.0d, 10,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
        assertEquals(1, context.subscriptionService.activeSubscriptionCount());

        client.deleteSubscription(subscriptionId);
        assertEquals(0, context.subscriptionService.activeSubscriptionCount());
        client.close();
    }
}
