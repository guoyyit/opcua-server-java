package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.client.LoopbackClient;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.PublishResult;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多会话并发与资源上限：会话/监控项/队列深度上限与慢消费隔离。
 */
class ConcurrencyAndLimitsTest {

    private LoopbackClient operatorOn(TestServerFactory.TestContext context) {
        LoopbackClient client = new LoopbackClient(context.server);
        client.openChannel(10_000L);
        client.createSession(5_000L);
        client.activateUser("operator", "operator-pass");
        return client;
    }

    @Test
    void sessionLimitReturnsDistinguishedError() {
        var context = TestServerFactory.newLimitedContext(2, 200, 2_000);
        var first = operatorOn(context);
        var second = operatorOn(context);

        var third = new LoopbackClient(context.server);
        third.openChannel(10_000L);
        OpcUaException exception = assertThrows(OpcUaException.class,
                () -> third.createSession(5_000L));
        assertEquals(StatusCode.BAD_SESSION_LIMIT_REACHED, exception.getStatusCode());

        // 关闭一个会话后名额释放。
        first.close();
        third.createSession(5_000L);
        second.close();
        third.close();
    }

    @Test
    void monitoredItemLimitReturnsDistinguishedError() {
        var context = TestServerFactory.newLimitedContext(10, 2, 2_000);
        var client = operatorOn(context);
        long subscriptionId = client.createSubscription(10.0d, 60_000L).getSubscriptionId();
        client.createMonitoredItem(subscriptionId, DemoAddressSpace.SET_POINT, 1L,
                new MonitoringParameters(5.0d, 5,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
        client.createMonitoredItem(subscriptionId, DemoAddressSpace.TEMPERATURE, 2L,
                new MonitoringParameters(5.0d, 5,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));

        OpcUaException exception = assertThrows(OpcUaException.class,
                () -> client.createMonitoredItem(subscriptionId, DemoAddressSpace.PRESSURE, 3L,
                        new MonitoringParameters(5.0d, 5,
                                MonitoringParameters.DiscardPolicy.DISCARD_OLDEST)));
        assertEquals(StatusCode.BAD_TOO_MANY_MONITORED_ITEMS, exception.getStatusCode());
        client.close();
    }

    @Test
    void notificationQueueLimitReturnsDistinguishedError() {
        var context = TestServerFactory.newLimitedContext(10, 200, 10);
        var client = operatorOn(context);
        long subscriptionId = client.createSubscription(10.0d, 60_000L).getSubscriptionId();

        // 单个监控项队列 10 恰好达上限。
        client.createMonitoredItem(subscriptionId, DemoAddressSpace.SET_POINT, 1L,
                new MonitoringParameters(5.0d, 10,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));

        // 再申请任何正队列深度都会超出会话级队列深度上限。
        OpcUaException exception = assertThrows(OpcUaException.class,
                () -> client.createMonitoredItem(subscriptionId, DemoAddressSpace.TEMPERATURE, 2L,
                        new MonitoringParameters(5.0d, 1,
                                MonitoringParameters.DiscardPolicy.DISCARD_OLDEST)));
        assertEquals(StatusCode.BAD_NOTIFICATION_QUEUE_LIMIT_REACHED,
                exception.getStatusCode());
        client.close();
    }

    @Test
    void slowConsumerDoesNotBlockOtherSessions() throws Exception {
        var context = TestServerFactory.newLimitedContext(10, 200, 2_000);
        var slow = operatorOn(context);
        var fast = operatorOn(context);

        long slowSubscription = slow.createSubscription(10.0d, 60_000L).getSubscriptionId();
        slow.createMonitoredItem(slowSubscription, DemoAddressSpace.SET_POINT, 1L,
                new MonitoringParameters(5.0d, 50,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));

        long fastSubscription = fast.createSubscription(10.0d, 60_000L).getSubscriptionId();
        fast.createMonitoredItem(fastSubscription, DemoAddressSpace.TEMPERATURE, 2L,
                new MonitoringParameters(5.0d, 50,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothWaiting = new CountDownLatch(2);
        AtomicInteger fastReceived = new AtomicInteger();

        // 慢消费者：发起一次长等待但故意不继续消费。
        Future<?> slowFuture = pool.submit(() -> {
            bothWaiting.countDown();
            try {
                slow.publish(slowSubscription, 300L, List.of());
            } finally {
                // 之后不再调用 publish，制造慢消费。
            }
        });

        // 快消费者持续生产/消费。
        Future<?> fastFuture = pool.submit(() -> {
            bothWaiting.countDown();
            try {
                for (int i = 0; i < 20; i++) {
                    fast.write(DemoAddressSpace.TEMPERATURE, 30.0d + i);
                    context.tickOnly(10L);
                    PublishResult result = fast.publish(fastSubscription, 300L, List.of());
                    if (!result.isKeepAlive()) {
                        fastReceived.incrementAndGet();
                    }
                }
            } catch (RuntimeException e) {
                throw new AssertionError("快会话被慢会话阻塞或受影响", e);
            }
        });

        assertTrue(bothWaiting.await(1, TimeUnit.SECONDS));
        fastFuture.get(5, TimeUnit.SECONDS);
        assertTrue(fastReceived.get() >= 10,
                "快会话应持续收到通知，实际收到 " + fastReceived.get());
        assertFalse(slowFuture.isDone() == false && fastFuture.isDone() == false);
        slowFuture.get(1, TimeUnit.SECONDS);

        pool.shutdown();
        slow.close();
        fast.close();
    }
}
