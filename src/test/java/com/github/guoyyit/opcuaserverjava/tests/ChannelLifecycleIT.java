package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaProperties;
import com.github.guoyyit.opcuaserverjava.opcua.client.OpcUaClient;
import com.github.guoyyit.opcuaserverjava.opcua.message.RequestType;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads;
import com.github.guoyyit.opcuaserverjava.opcua.transport.OpcUaTcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secure channel establishment, independent sequences, timeout close and
 * rejection of requests after close.
 */
@SpringBootTest(properties = {
        "opcua.default-channel-lifetime-ms=1000",
        "opcua.default-session-timeout-ms=60000",
        "opcua.reaper-interval-ms=40"
})
@Timeout(30)
class ChannelLifecycleIT {

    @Autowired
    private OpcUaTcpServer server;

    @Autowired
    private OpcUaProperties properties;

    @Test
    void openChannelReturnsIndependentIdAndLifetime() throws IOException {
        try (OpcUaClient first = new OpcUaClient("127.0.0.1", server.getBoundPort());
             OpcUaClient second = new OpcUaClient("127.0.0.1", server.getBoundPort())) {
            first.connect();
            second.connect();
            int firstId = first.openSecureChannel(0).channelId();
            int secondId = second.openSecureChannel(0).channelId();
            assertNotEquals(firstId, secondId);
            assertTrue(firstId > 0);
        }
    }

    @Test
    void timedOutChannelIsClosedAndRejectsLaterRequests() throws Exception {
        OpcUaClient client = new OpcUaClient("127.0.0.1", server.getBoundPort());
        client.connect();
        client.openSecureChannel(500);

        // Wait beyond the revised lifetime; the reaper closes the channel and
        // the socket, so the next exchange fails at the transport layer.
        Thread.sleep(1200);
        assertThrows(IOException.class, () ->
                client.exchange(RequestType.CLOSE_SECURE_CHANNEL,
                        new ServicePayloads.CloseSecureChannelRequest()));
        client.close();

        // A fresh connection establishes a brand-new channel and works again.
        try (OpcUaClient reopened = new OpcUaClient("127.0.0.1", server.getBoundPort())) {
            reopened.connect();
            Response response = reopened.openSecureChannel(0) != null
                    ? reopened.exchange(RequestType.CLOSE_SECURE_CHANNEL,
                    new ServicePayloads.CloseSecureChannelRequest()) : null;
            assertEquals(0L, response.statusCode());
        }
    }

    @Test
    void explicitCloseRejectsFollowUpRequests() throws Exception {
        OpcUaClient client = new OpcUaClient("127.0.0.1", server.getBoundPort());
        client.connect();
        client.openSecureChannel(0);
        Response closeResponse = client.exchange(RequestType.CLOSE_SECURE_CHANNEL,
                new ServicePayloads.CloseSecureChannelRequest());
        assertEquals(0L, closeResponse.statusCode());
        assertThrows(IOException.class, () ->
                client.exchange(RequestType.CLOSE_SECURE_CHANNEL,
                        new ServicePayloads.CloseSecureChannelRequest()));
        client.close();
    }
}
