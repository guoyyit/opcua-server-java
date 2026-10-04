package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.client.LoopbackClient;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 通道层：建立/独立序列号/超时关闭/关闭后拒绝/级联回收。
 */
class SecureChannelTest {

    private final TestServerFactory.TestContext context = TestServerFactory.newContext();

    @Test
    void openChannelAndIndependentSequences() {
        var first = context.server.openChannel(10_000L);
        var second = context.server.openChannel(10_000L);
        assertNotEquals(first.getChannelId(), second.getChannelId());

        // 两条通道的请求序列号独立计数，均从 1 开始。
        context.server.createSession(first.getChannelId(), 1L, 5_000L);
        context.server.createSession(second.getChannelId(), 1L, 5_000L);
        context.server.createSession(first.getChannelId(), 2L, 5_000L);
        assertEquals(2, context.channelService.activeChannelCount());
    }

    @Test
    void rejectsInvalidAndDuplicateSequenceNumber() {
        var channel = context.server.openChannel(10_000L);
        long channelId = channel.getChannelId();

        // 必须从 1 开始。
        assertThrowsBad(StatusCode.BAD_SEQUENCE_NUMBER_INVALID,
                () -> context.server.createSession(channelId, 5L, 5_000L));
        // 跳号/重复都拒绝，且期望值停留在 1。
        assertThrowsBad(StatusCode.BAD_SEQUENCE_NUMBER_INVALID,
                () -> context.server.createSession(channelId, 2L, 5_000L));
        // 通道仍可用。
        context.server.createSession(channelId, 1L, 5_000L);
    }

    @Test
    void unknownAndClosedChannelAreRejected() {
        assertThrowsBad(StatusCode.BAD_SECURE_CHANNEL_ID_INVALID,
                () -> context.server.createSession(999L, 1L, 5_000L));

        var channel = context.server.openChannel(10_000L);
        context.server.closeChannel(channel.getChannelId());
        assertThrowsBad(StatusCode.BAD_SECURE_CHANNEL_ID_INVALID,
                () -> context.server.createSession(channel.getChannelId(), 1L, 5_000L));
    }

    @Test
    void timeoutClosesChannelAndRejectsLaterRequests() {
        var channel = context.server.openChannel(200L);
        long channelId = channel.getChannelId();
        var session = context.server.createSession(channelId, 1L, 5_000L);
        context.server.activateSession(channelId, 2L, session.getSessionId(),
                com.github.guoyyit.opcuaserverjava.opcua.core.UserIdentityToken.anonymous());

        // 超过通道生命周期后下一次请求被拒绝，且通道被关闭。
        context.advanceWithoutChannelReap(250L);
        assertThrowsBad(StatusCode.BAD_SECURE_CHANNEL_TIMEOUT,
                () -> context.server.read(channelId, 3L, session.getSessionId(),
                        DemoAddressSpace.TEMPERATURE));

        // 必须重新建立通道。
        assertThrowsBad(StatusCode.BAD_SECURE_CHANNEL_ID_INVALID,
                () -> context.server.read(channelId, 3L, session.getSessionId(),
                        DemoAddressSpace.TEMPERATURE));
        assertEquals(0, context.channelService.activeChannelCount());
    }

    @Test
    void closingChannelReclaimsSessionsAndSubscriptions() {
        try (LoopbackClient client = new LoopbackClient(context.server)) {
            client.openChannel(10_000L);
            client.createSession(5_000L);
            client.activateAnonymous();
            var subscription = client.createSubscription(10.0d, 5_000L);
            client.createMonitoredItem(subscription.getSubscriptionId(),
                    DemoAddressSpace.TEMPERATURE, 1L,
                    new MonitoringParameters(5.0d, 10,
                            MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));

            assertEquals(1, context.sessionService.activeSessionCount());
            assertEquals(1, context.subscriptionService.activeSubscriptionCount());

            client.closeChannel();

            assertEquals(0, context.sessionService.activeSessionCount());
            assertEquals(0, context.subscriptionService.activeSubscriptionCount());
        }
    }

    private static void assertThrowsBad(StatusCode expected, Runnable action) {
        OpcUaException exception = assertThrows(OpcUaException.class, action::run);
        assertEquals(expected, exception.getStatusCode());
    }
}
