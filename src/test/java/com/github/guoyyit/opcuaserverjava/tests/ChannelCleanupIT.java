package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaStats;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.client.OpcUaClient;
import com.github.guoyyit.opcuaserverjava.opcua.message.RequestType;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.CreateSubscriptionResponse;
import com.github.guoyyit.opcuaserverjava.opcua.model.DiscardPolicy;
import com.github.guoyyit.opcuaserverjava.opcua.model.IdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.transport.OpcUaTcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Closing/timeout of a secure channel cascades to its sessions, subscriptions
 * and monitored items so no resource keeps being held.
 */
@SpringBootTest(properties = {
        "opcua.default-channel-lifetime-ms=800",
        "opcua.default-session-timeout-ms=60000",
        "opcua.reaper-interval-ms=40",
        "opcua.min-sampling-interval-ms=10",
        "opcua.max-monitored-items-per-subscription=10",
        "opcua.max-notification-queue-depth=100",
        "opcua.max-subscriptions-per-session=5"
})
@Timeout(30)
class ChannelCleanupIT {

    @Autowired
    private OpcUaTcpServer server;

    @Autowired
    private OpcUaStats stats;

    @Test
    void channelTimeoutReclaimsSessionSubscriptionAndItems() throws Exception {
        int channelBase = stats.channelCount();
        int sessionBase = stats.sessionCount();
        int subBase = stats.subscriptionCount();
        int itemBase = stats.monitoredItemCount();

        OpcUaClient client = new OpcUaClient("127.0.0.1", server.getBoundPort());
        client.connect();
        client.openSecureChannel(500);
        Response created = client.exchange(RequestType.CREATE_SESSION,
                new ServicePayloads.CreateSessionRequest(0));
        String token = client.objectMapper()
                .treeToValue(created.payload(),
                        ServicePayloads.CreateSessionResponse.class).sessionToken();
        client.exchange(RequestType.ACTIVATE_SESSION,
                new ServicePayloads.ActivateSessionRequest(token, IdentityToken.anonymous()));
        client.setSessionToken(token);

        Response subResponse = client.exchange(RequestType.CREATE_SUBSCRIPTION,
                new ServicePayloads.CreateSubscriptionRequest(token, 40, 0, 10, 1, 60_000));
        long subscriptionId = client.objectMapper()
                .treeToValue(subResponse.payload(), CreateSubscriptionResponse.class)
                .subscriptionId();
        client.exchange(RequestType.CREATE_MONITORED_ITEMS,
                new ServicePayloads.CreateMonitoredItemsRequest(token, subscriptionId,
                        List.of(
                                new ServicePayloads.MonitoredItemCreateRequest(
                                        NodeId.numeric(2001), 10, 20, DiscardPolicy.DISCARD_OLDEST),
                                new ServicePayloads.MonitoredItemCreateRequest(
                                        NodeId.numeric(2003), 10, 20, DiscardPolicy.DISCARD_OLDEST))));

        assertEquals(channelBase + 1, stats.channelCount());
        assertEquals(sessionBase + 1, stats.sessionCount());
        assertEquals(subBase + 1, stats.subscriptionCount());
        assertEquals(itemBase + 2, stats.monitoredItemCount());

        // Let the channel time out; the reaper closes it and must cascade.
        waitUntil(() -> stats.channelCount() == channelBase, 3000);
        waitUntil(() -> stats.sessionCount() == sessionBase, 3000);
        waitUntil(() -> stats.subscriptionCount() == subBase
                && stats.monitoredItemCount() == itemBase, 3000);

        client.close();
    }

    private void waitUntil(Condition condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(true, condition.matches(), "resource was not reclaimed before timeout");
    }

    @FunctionalInterface
    private interface Condition {
        boolean matches();
    }
}
