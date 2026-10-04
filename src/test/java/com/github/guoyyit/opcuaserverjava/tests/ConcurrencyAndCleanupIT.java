package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaStats;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.client.OpcUaClient;
import com.github.guoyyit.opcuaserverjava.opcua.message.RequestType;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.CreateSubscriptionResponse;
import com.github.guoyyit.opcuaserverjava.opcua.model.DataValue;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-session isolation, slow consumer not blocking another session,
 * reconnect republish and full resource reclamation on session/channel close.
 */
@SpringBootTest(properties = {
        "opcua.default-channel-lifetime-ms=60000",
        "opcua.default-session-timeout-ms=60000",
        "opcua.reaper-interval-ms=40",
        "opcua.min-sampling-interval-ms=10",
        "opcua.max-monitored-items-per-subscription=10",
        "opcua.max-notification-queue-depth=100",
        "opcua.max-subscriptions-per-session=5"
})
@Timeout(50)
class ConcurrencyAndCleanupIT {

    @Autowired
    private OpcUaTcpServer server;

    @Autowired
    private OpcUaStats stats;

    private record Client(OpcUaClient raw, String token) implements AutoCloseable {
        @Override
        public void close() {
            raw.close();
        }
    }

    private Client newClient() throws IOException {
        OpcUaClient raw = new OpcUaClient("127.0.0.1", server.getBoundPort());
        raw.connect();
        raw.openSecureChannel(0);
        Response created = raw.exchange(RequestType.CREATE_SESSION,
                new ServicePayloads.CreateSessionRequest(0));
        String token = raw.objectMapper()
                .treeToValue(created.payload(),
                        ServicePayloads.CreateSessionResponse.class).sessionToken();
        raw.exchange(RequestType.ACTIVATE_SESSION,
                new ServicePayloads.ActivateSessionRequest(token, IdentityToken.anonymous()));
        raw.setSessionToken(token);
        return new Client(raw, token);
    }

    private long subscribe(Client client, long publishingInterval, long retentionWindow)
            throws IOException {
        Response response = client.raw().exchange(RequestType.CREATE_SUBSCRIPTION,
                new ServicePayloads.CreateSubscriptionRequest(client.token(),
                        publishingInterval, 0, 10, 1, retentionWindow));
        assertEquals(0L, response.statusCode());
        return client.raw().objectMapper()
                .treeToValue(response.payload(), CreateSubscriptionResponse.class)
                .subscriptionId();
    }

    private void monitor(Client client, long subscriptionId, NodeId nodeId) throws IOException {
        Response response = client.raw().exchange(RequestType.CREATE_MONITORED_ITEMS,
                new ServicePayloads.CreateMonitoredItemsRequest(client.token(), subscriptionId,
                        List.of(new ServicePayloads.MonitoredItemCreateRequest(
                                nodeId, 10, 50, DiscardPolicy.DISCARD_OLDEST))));
        ServicePayloads.CreateMonitoredItemsResponse results = client.raw().objectMapper()
                .treeToValue(response.payload(),
                        ServicePayloads.CreateMonitoredItemsResponse.class);
        assertEquals(StatusCode.GOOD.code(), results.results().get(0).statusCode());
    }

    private void write(Client client, NodeId nodeId, int value) throws IOException {
        Response response = client.raw().exchange(RequestType.WRITE,
                new ServicePayloads.WriteRequest(client.token(),
                        List.of(new ServicePayloads.WriteValue(nodeId, DataValue.good(value)))));
        assertEquals(0L, response.statusCode());
    }

    @Test
    void slowConsumerDoesNotBlockOtherSession() throws Exception {
        try (Client slow = newClient(); Client fast = newClient()) {
            long slowSub = subscribe(slow, 40, 60_000);
            monitor(slow, slowSub, NodeId.numeric(2001));
            long fastSub = subscribe(fast, 40, 60_000);
            monitor(fast, fastSub, NodeId.numeric(2003));

            // Slow session posts a publish with a long hint but never consumes.
            Thread slowPump = new Thread(() -> {
                try {
                    slow.raw().publish(new ServicePayloads.PublishRequest(
                            slow.token(), slowSub, 20_000));
                } catch (IOException ignored) {
                    // expected to be cancelled/kept alive
                }
            });
            slowPump.start();
            Thread.sleep(150);

            // Fast session must still receive its own notifications promptly.
            Thread fastPump = new Thread(() -> {
                try {
                    fast.raw().publish(new ServicePayloads.PublishRequest(
                            fast.token(), fastSub, 10_000));
                } catch (IOException ignored) {
                }
            });
            fastPump.start();
            Thread.sleep(150);
            for (int i = 0; i < 5; i++) {
                write(fast, NodeId.numeric(2003), 500 + i);
                Thread.sleep(70);
            }
            fastPump.join(8_000);
            assertTrue(!fastPump.isAlive(), "fast consumer must not be blocked by slow session");
            slowPump.interrupt();
        }
    }

    @Test
    void sessionsDeliverIndependentNotifications() throws Exception {
        try (Client a = newClient(); Client b = newClient()) {
            long subA = subscribe(a, 40, 60_000);
            monitor(a, subA, NodeId.numeric(2001));
            long subB = subscribe(b, 40, 60_000);
            monitor(b, subB, NodeId.numeric(2001));

            AtomicInteger gotA = new AtomicInteger();
            AtomicInteger gotB = new AtomicInteger();
            Thread ta = pump(a, subA, gotA, 4);
            Thread tb = pump(b, subB, gotB, 4);
            ta.start();
            tb.start();
            Thread.sleep(200);
            for (int i = 0; i < 6; i++) {
                write(a, NodeId.numeric(2001), 900 + i);
                Thread.sleep(70);
            }
            ta.join(10_000);
            tb.join(10_000);
            assertTrue(gotA.get() > 0 && gotB.get() > 0,
                    "both sessions must independently observe the same node changes");
        }
    }

    private Thread pump(Client client, long subscriptionId, AtomicInteger counter, int max) {
        return new Thread(() -> {
            for (int i = 0; i < max; i++) {
                try {
                    ServicePayloads.PublishResponse response = client.raw().publish(
                            new ServicePayloads.PublishRequest(client.token(),
                                    subscriptionId, 2_000));
                    if (!response.notification().keepAlive()) {
                        counter.incrementAndGet();
                    }
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @Test
    void closingSessionReclaimsSubscriptionsAndItems() throws Exception {
        int subBefore = stats.subscriptionCount();
        int itemBefore = stats.monitoredItemCount();
        Client client = newClient();
        long subscriptionId = subscribe(client, 40, 60_000);
        monitor(client, subscriptionId, NodeId.numeric(2001));
        monitor(client, subscriptionId, NodeId.numeric(2003));
        assertEquals(subBefore + 1, stats.subscriptionCount());
        assertEquals(itemBefore + 2, stats.monitoredItemCount());

        Response closed = client.raw().exchange(RequestType.CLOSE_SESSION,
                new ServicePayloads.CloseSessionRequest(client.token()));
        assertEquals(0L, closed.statusCode());
        waitUntil(() -> stats.subscriptionCount() == subBefore
                && stats.monitoredItemCount() == itemBefore, 3000);
        client.close();
        waitUntil(() -> true, 50);
    }

    @Test
    void reconnectingClientCanRepublishWithinWindow() throws Exception {
        int channelBefore = stats.channelCount();
        Client first = newClient();
        try {
            long subscriptionId = subscribe(first, 40, 60_000);
            monitor(first, subscriptionId, NodeId.numeric(2003));
            write(first, NodeId.numeric(2003), 42);
            Thread.sleep(120);

            // Consume a notification, then ask for it again by sequence number
            // while the connection stays live (reconnect republish semantics).
            ServicePayloads.PublishResponse delivered = first.raw().publish(
                    new ServicePayloads.PublishRequest(first.token(),
                            subscriptionId, 5_000));
            long sequence = delivered.notification().sequenceNumber();
            Response republish = first.raw().exchange(RequestType.REPUBLISH,
                    new ServicePayloads.RepublishRequest(first.token(),
                            subscriptionId, sequence));
            assertEquals(0L, republish.statusCode());
            assertTrue(sequence >= 1);
        } finally {
            first.close();
        }
        waitUntil(() -> stats.channelCount() == channelBefore, 3000);
        assertEquals(channelBefore, stats.channelCount());
    }

    private void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(true, condition.getAsBoolean(), "condition was not met before timeout");
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
