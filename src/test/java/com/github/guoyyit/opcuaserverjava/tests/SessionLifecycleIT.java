package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaStats;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.client.OpcUaClient;
import com.github.guoyyit.opcuaserverjava.opcua.message.RequestType;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.CreateSessionResponse;
import com.github.guoyyit.opcuaserverjava.opcua.model.IdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.transport.OpcUaTcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Session create/activate/close, anonymous and credential activation,
 * distinguishable rejection codes, idle expiry and unactivated rejection.
 */
@SpringBootTest(properties = {
        "opcua.default-channel-lifetime-ms=60000",
        "opcua.default-session-timeout-ms=700",
        "opcua.reaper-interval-ms=40"
})
@Timeout(30)
class SessionLifecycleIT {

    @Autowired
    private OpcUaTcpServer server;

    @Autowired
    private OpcUaStats stats;

    private OpcUaClient newClient() throws IOException {
        OpcUaClient client = new OpcUaClient("127.0.0.1", server.getBoundPort());
        client.connect();
        client.openSecureChannel(0);
        return client;
    }

    private String createSession(OpcUaClient client) throws IOException {
        Response response = client.exchange(RequestType.CREATE_SESSION,
                new ServicePayloads.CreateSessionRequest(0));
        assertEquals(0L, response.statusCode());
        return client.objectMapper()
                .treeToValue(response.payload(), CreateSessionResponse.class)
                .sessionToken();
    }

    private long activate(OpcUaClient client, String token, IdentityToken identity)
            throws IOException {
        return client.exchange(RequestType.ACTIVATE_SESSION,
                new ServicePayloads.ActivateSessionRequest(token, identity)).statusCode();
    }

    @Test
    void unactivatedSessionRejectsBrowse() throws Exception {
        try (OpcUaClient client = newClient()) {
            String token = createSession(client);
            Response browse = client.exchange(RequestType.BROWSE,
                    new ServicePayloads.BrowseRequest(token, NodeId.numeric(84), 10));
            assertEquals(StatusCode.BAD_SESSION_NOT_ACTIVATED.code(), browse.statusCode());
        }
    }

    @Test
    void anonymousActivationAllowsBrowse() throws Exception {
        try (OpcUaClient client = newClient()) {
            String token = createSession(client);
            assertEquals(0L, activate(client, token, IdentityToken.anonymous()));
            Response browse = client.exchange(RequestType.BROWSE,
                    new ServicePayloads.BrowseRequest(token, NodeId.numeric(84), 10));
            assertEquals(0L, browse.statusCode());
        }
    }

    @Test
    void validAndInvalidCredentialsAreDistinguished() throws Exception {
        try (OpcUaClient client = newClient()) {
            String good = createSession(client);
            assertEquals(0L,
                    activate(client, good, IdentityToken.username("operator", "operator-pass")));

            String badPassword = createSession(client);
            assertEquals(StatusCode.BAD_IDENTITY_TOKEN_REJECTED.code(),
                    activate(client, badPassword,
                            IdentityToken.username("operator", "wrong-password")));

            String unknownUser = createSession(client);
            // Same distinguishable code; no detail revealing whether the user exists.
            assertEquals(StatusCode.BAD_IDENTITY_TOKEN_REJECTED.code(),
                    activate(client, unknownUser,
                            IdentityToken.username("nobody", "whatever")));
        }
    }

    @Test
    void idleSessionExpiresAndRejectsRequests() throws Exception {
        try (OpcUaClient client = newClient()) {
            String token = createSession(client);
            assertEquals(0L, activate(client, token, IdentityToken.anonymous()));
            int before = stats.sessionCount();
            assertEquals(1, before);

            // No activity beyond the idle timeout.
            Thread.sleep(1100);
            Response browse = client.exchange(RequestType.BROWSE,
                    new ServicePayloads.BrowseRequest(token, NodeId.numeric(84), 10));
            long status = browse.statusCode();
            org.junit.jupiter.api.Assertions.assertTrue(
                    status == StatusCode.BAD_SESSION_CLOSED.code()
                            || status == StatusCode.BAD_SESSION_ID_INVALID.code(),
                    "expected an expired-session code but was 0x" + Long.toHexString(status));
        }
    }

    @Test
    void closeSessionRejectsLaterUse() throws Exception {
        try (OpcUaClient client = newClient()) {
            String token = createSession(client);
            assertEquals(0L, activate(client, token, IdentityToken.anonymous()));
            Response closed = client.exchange(RequestType.CLOSE_SESSION,
                    new ServicePayloads.CloseSessionRequest(token));
            assertEquals(0L, closed.statusCode());
            Response browse = client.exchange(RequestType.BROWSE,
                    new ServicePayloads.BrowseRequest(token, NodeId.numeric(84), 10));
            assertEquals(StatusCode.BAD_SESSION_ID_INVALID.code(), browse.statusCode());
        }
    }
}
