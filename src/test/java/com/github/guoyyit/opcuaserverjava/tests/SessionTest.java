package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.client.LoopbackClient;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseDirection;
import com.github.guoyyit.opcuaserverjava.opcua.core.MonitoringParameters;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.UserIdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 会话层：创建/激活/匿名与凭据/空闲失效/关闭回收订阅。
 */
class SessionTest {

    private final TestServerFactory.TestContext context = TestServerFactory.newContext();

    private LoopbackClient connectedClient() {
        LoopbackClient client = new LoopbackClient(context.server);
        client.openChannel(10_000L);
        return client;
    }

    @Test
    void inactivatedSessionRejectsBrowseReadWrite() {
        var client = connectedClient();
        client.createSession(5_000L);

        assertBad(StatusCode.BAD_SESSION_NOT_ACTIVATED,
                () -> client.browse(DemoAddressSpace.DEVICE_A, BrowseDirection.FORWARD,
                        null, 10));
        assertBad(StatusCode.BAD_SESSION_NOT_ACTIVATED,
                () -> client.read(DemoAddressSpace.TEMPERATURE));
        assertBad(StatusCode.BAD_SESSION_NOT_ACTIVATED,
                () -> client.write(DemoAddressSpace.TEMPERATURE, 22.0d));
        client.close();
    }

    @Test
    void anonymousActivationAllowed() {
        var client = connectedClient();
        client.createSession(5_000L);
        client.activateAnonymous();
        // 激活后可浏览、可读。
        var result = client.browse(DemoAddressSpace.DEVICE_A,
                BrowseDirection.FORWARD, null, 10);
        assertEquals(3, result.getReferences().size());
        assertEquals(21.5d, client.read(DemoAddressSpace.TEMPERATURE).getValue());
        // 匿名不可写。
        assertBad(StatusCode.BAD_USER_ACCESS_DENIED,
                () -> client.write(DemoAddressSpace.TEMPERATURE, 22.0d));
        client.close();
    }

    @Test
    void credentialActivationGrantsWrite() {
        var client = connectedClient();
        client.createSession(5_000L);
        client.activateUser("operator", "operator-pass");
        client.write(DemoAddressSpace.TEMPERATURE, 22.5d);
        assertEquals(22.5d, client.read(DemoAddressSpace.TEMPERATURE).getValue());
        client.close();
    }

    @Test
    void credentialRejectionReturnsDistinguishedCode() {
        // 用户不存在 与 口令错误 返回完全相同的错误码与文案，不泄露细节。
        OpcUaException missingUser = activateAndCapture("ghost", "whatever");
        OpcUaException wrongPassword = activateAndCapture("operator", "bad-pass");
        assertEquals(StatusCode.BAD_IDENTITY_TOKEN_REJECTED, missingUser.getStatusCode());
        assertEquals(StatusCode.BAD_IDENTITY_TOKEN_REJECTED, wrongPassword.getStatusCode());
        assertEquals(missingUser.getMessage(), wrongPassword.getMessage());

        // 畸形令牌（空）与凭据拒绝是不同错误码。
        long channelId = clientChannelIdForRawCall();
        long sessionId = createRawSession(channelId);
        OpcUaException invalidToken = assertThrows(OpcUaException.class,
                () -> context.server.activateSession(channelId, 2L, sessionId,
                        UserIdentityToken.username("", "x")));
        assertEquals(StatusCode.BAD_IDENTITY_TOKEN_REJECTED, invalidToken.getStatusCode());
    }

    @Test
    void idleSessionExpires() {
        var client = connectedClient();
        client.createSession(500L);
        client.activateAnonymous();

        context.tickOnly(300L);
        // 活动后继续有效。
        client.read(DemoAddressSpace.TEMPERATURE);

        context.tickOnly(600L);
        assertBad(StatusCode.BAD_SESSION_TIMEOUT,
                () -> client.read(DemoAddressSpace.TEMPERATURE));
        client.closeChannel();
    }

    @Test
    void closingSessionReclaimsSubscriptions() {
        var client = connectedClient();
        client.createSession(5_000L);
        client.activateAnonymous();
        var subscription = client.createSubscription(10.0d, 5_000L);
        client.createMonitoredItem(subscription.getSubscriptionId(),
                DemoAddressSpace.TEMPERATURE, 1L,
                new MonitoringParameters(5.0d, 10,
                        MonitoringParameters.DiscardPolicy.DISCARD_OLDEST));
        assertEquals(1, context.subscriptionService.activeSubscriptionCount());

        client.closeSession();
        assertEquals(0, context.subscriptionService.activeSubscriptionCount());
        client.closeChannel();
    }

    private OpcUaException activateAndCapture(String user, String password) {
        var client = connectedClient();
        client.createSession(5_000L);
        OpcUaException exception = assertThrows(OpcUaException.class,
                () -> client.activateUser(user, password));
        client.closeChannel();
        return exception;
    }

    private long clientChannelIdForRawCall() {
        return context.server.openChannel(10_000L).getChannelId();
    }

    private long createRawSession(long channelId) {
        return context.server.createSession(channelId, 1L, 5_000L).getSessionId();
    }

    private static void assertBad(StatusCode expected, Runnable action) {
        OpcUaException exception = assertThrows(OpcUaException.class, action::run);
        assertEquals(expected, exception.getStatusCode());
    }
}
