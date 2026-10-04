package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.client.LoopbackClient;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.PublishResult;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 发布/重发布：保留窗口内可重发、超窗回收、确认后释放、迁移会话后重发。
 */
class PublishRepublishTest {

    private final TestServerFactory.TestContext context = TestServerFactory.newContext();

    private LoopbackClient operator() {
        LoopbackClient client = new LoopbackClient(context.server);
        client.openChannel(10_000L);
        client.createSession(5_000L);
        client.activateUser("operator", "operator-pass");
        return client;
    }

    private long producingSubscription(LoopbackClient client, long retentionMillis) {
        long subscriptionId = client.createSubscription(10.0d, retentionMillis).getSubscriptionId();
        client.createMonitoredItem(subscriptionId, DemoAddressSpace.SET_POINT, 1L,
                new MonitoringParameters(5.0d, 100,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
        // 产生序列号 1..3 的三条消息但不确认。
        for (int value = 25; value <= 27; value++) {
            client.write(DemoAddressSpace.SET_POINT, value);
            context.tickOnly(10L);
            client.publish(subscriptionId, 50L, List.of());
        }
        return subscriptionId;
    }

    @Test
    void unacknowledgedMessagesCanBeRepublishedWithinWindow() {
        var client = operator();
        long subscriptionId = producingSubscription(client, 60_000L);

        PublishResult republished = client.republish(subscriptionId, 2L);
        assertEquals(2L, republished.getMessage().getSequenceNumber());
        assertEquals(26, republished.getMessage().getNotifications().get(0).value().getValue());

        // 原始 1 与 3 仍可用。
        assertEquals(1L, client.republish(subscriptionId, 1L).getMessage().getSequenceNumber());
        assertEquals(3L, client.republish(subscriptionId, 3L).getMessage().getSequenceNumber());
        client.close();
    }

    @Test
    void messagesOutsideRetentionWindowAreReclaimed() {
        var client = operator();
        long subscriptionId = producingSubscription(client, 100L);

        // 在保留窗口内仍可重发。
        assertEquals(1L, client.republish(subscriptionId, 1L).getMessage().getSequenceNumber());

        // 超出时间窗口后消息被回收，返回可区分错误码。
        context.tickOnly(150L);
        OpcUaException exception = assertThrows(OpcUaException.class,
                () -> client.republish(subscriptionId, 1L));
        assertEquals(StatusCode.BAD_MESSAGE_NOT_AVAILABLE, exception.getStatusCode());

        // 从未存在/已回收的号也返回同一错误码。
        OpcUaException neverExisted = assertThrows(OpcUaException.class,
                () -> client.republish(subscriptionId, 999L));
        assertEquals(StatusCode.BAD_MESSAGE_NOT_AVAILABLE, neverExisted.getStatusCode());
        client.close();
    }

    @Test
    void acknowledgedMessagesAreReleased() {
        var client = operator();
        long subscriptionId = producingSubscription(client, 60_000L);

        // 确认 1、2 后不可再重发。
        client.publish(subscriptionId, 50L, List.of(1L, 2L));
        assertBadMessage(() -> client.republish(subscriptionId, 1L));
        assertBadMessage(() -> client.republish(subscriptionId, 2L));
        // 未确认的 3 仍在窗口内。
        assertEquals(3L, client.republish(subscriptionId, 3L).getMessage().getSequenceNumber());
        client.close();
    }

    @Test
    void republishAfterReconnectDeliversMissedSequence() {
        // 模拟“客户端重启”：在旧通道/旧会话上先迁移订阅到新会话，
        // 旧会话随后关闭；新会话在保留窗口内重发漏掉的序列号。
        var oldClient = operator();
        long oldSessionId = oldClient.getSessionId();
        long subscriptionId = producingSubscription(oldClient, 60_000L);

        var newClient = operator();
        long newSessionId = newClient.getSessionId();

        // 由新通道上的新会话发起迁移，引用旧会话与订阅（此时旧会话仍有效）。
        newClient.transferSubscription(oldSessionId, newSessionId, subscriptionId);
        oldClient.close();

        PublishResult recovered = newClient.republish(subscriptionId, 2L);
        assertEquals(2L, recovered.getMessage().getSequenceNumber());
        assertEquals(26, recovered.getMessage().getNotifications().get(0).value().getValue());

        // 新会话也能正常继续接收新消息。
        newClient.write(DemoAddressSpace.SET_POINT, 40);
        context.tickOnly(10L);
        PublishResult next = newClient.publish(subscriptionId, 50L, List.of());
        assertEquals(4L, next.getMessage().getSequenceNumber());
        newClient.close();
    }

    private static void assertBadMessage(Runnable action) {
        OpcUaException exception = assertThrows(OpcUaException.class, action::run);
        assertEquals(StatusCode.BAD_MESSAGE_NOT_AVAILABLE, exception.getStatusCode());
    }
}
