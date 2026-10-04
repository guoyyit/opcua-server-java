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
import com.github.guoyyit.opcuaserverjava.opcua.model.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.transport.OpcUaTcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Monitored item sampling, ordered publishing with monotonic sequence numbers,
 * republish inside/outside the retention window, unpublish resource release and
 * resource-limit error codes.
 */
@SpringBootTest(properties = {
        "opcua.default-channel-lifetime-ms=60000",
        "opcua.default-session-timeout-ms=60000",
        "opcua.reaper-interval-ms=40",
        "opcua.min-sampling-interval-ms=10",
        "opcua.max-monitored-items-per-subscription=5",
        "opcua.max-notification-queue-depth=100",
        "opcua.max-subscriptions-per-session=3"
})
@Timeout(40)
class SubscriptionIT {

    @Autowired
    private OpcUaTcpServer server;

    @Autowired
    private OpcUaStats stats;

    private static final class SessionClient implements AutoCloseable {
        final OpcUaClient client;
        final String token;

        SessionClient(OpcUaClient client, String token) {
            this.client = client;
            this.token = token;
        }

        @Override
        public void close() throws IOException {
            client.close();
        }
    }

    private SessionClient newSession() throws IOException {
        OpcUaClient client = new OpcUaClient("127.0.0.1", server.getBoundPort());
        client.connect();
        client.openSecureChannel(0);
        Response created = client.exchange(RequestType.CREATE_SESSION,
                new ServicePayloads.CreateSessionRequest(0));
        String token = client.objectMapper()
                .treeToValue(created.payload(),
                        ServicePayloads.CreateSessionResponse.class).sessionToken();
        client.exchange(RequestType.ACTIVATE_SESSION,
                new ServicePayloads.ActivateSessionRequest(token, IdentityToken.anonymous()));
        client.setSessionToken(token);
        return new SessionClient(client, token);
    }

    private long createSubscription(SessionClient session, long publishingInterval,
                                    long retentionWindow) throws IOException {
        Response response = session.client.exchange(RequestType.CREATE_SUBSCRIPTION,
                new ServicePayloads.CreateSubscriptionRequest(session.token,
                        publishingInterval, 0, 10, 1, retentionWindow));
        assertEquals(0L, response.statusCode());
        return session.client.objectMapper()
                .treeToValue(response.payload(), CreateSubscriptionResponse.class)
                .subscriptionId();
    }

    private long createMonitoredItem(SessionClient session, long subscriptionId, NodeId nodeId,
                                     long samplingMs, int queueSize) throws IOException {
        Response response = session.client.exchange(RequestType.CREATE_MONITORED_ITEMS,
                new ServicePayloads.CreateMonitoredItemsRequest(session.token, subscriptionId,
                        List.of(new ServicePayloads.MonitoredItemCreateRequest(
                                nodeId, samplingMs, queueSize, DiscardPolicy.DISCARD_OLDEST))));
        ServicePayloads.CreateMonitoredItemsResponse results = session.client.objectMapper()
                .treeToValue(response.payload(),
                        ServicePayloads.CreateMonitoredItemsResponse.class);
        assertEquals(StatusCode.GOOD.code(), results.results().get(0).statusCode());
        return results.results().get(0).monitoredItemId();
    }

    private void writeValue(SessionClient session, NodeId nodeId, int value) throws IOException {
        Response response = session.client.exchange(RequestType.WRITE,
                new ServicePayloads.WriteRequest(session.token,
                        List.of(new ServicePayloads.WriteValue(nodeId, DataValue.good(value)))));
        assertEquals(0L, response.statusCode());
    }

    @Test
    void notificationsArriveWithMonotonicSequenceNumbers() throws Exception {
        try (SessionClient session = newSession()) {
            long subscriptionId = createSubscription(session, 60, 60_000);
            createMonitoredItem(session, subscriptionId, NodeId.numeric(2001), 10, 50);

            // Start a consumer that waits for publish notifications.
            final java.util.concurrent.atomic.AtomicReference<ServicePayloads.PublishResponse>
                    first = new java.util.concurrent.atomic.AtomicReference<>();
            Thread consumer = new Thread(() -> {
                try {
                    first.set(session.client.publish(new ServicePayloads.PublishRequest(
                            session.token, subscriptionId, 5_000)));
                } catch (IOException e) {
                    // keep-alive or timeout; acceptable
                }
            });
            consumer.start();
            Thread.sleep(100);
            for (int i = 0; i < 5; i++) {
                writeValue(session, NodeId.numeric(2001), 100 + i);
                Thread.sleep(90);
            }
            consumer.join(8_000);
            assertTrue(first.get() != null, "expected at least one live notification");
            assertTrue(first.get().notification().sequenceNumber() >= 1);

// Draining subsequent notifications must keep strictly increasing sequence numbers.
            long previous = first.get().notification().sequenceNumber();
            for (int i = 0; i < 6; i++) {
                try {
                    ServicePayloads.PublishResponse next = session.client.publish(
                            new ServicePayloads.PublishRequest(session.token, subscriptionId, 800));
                    if (!next.notification().keepAlive()) {
                        assertTrue(next.notification().sequenceNumber() > previous,
                                "sequence numbers must be strictly increasing");
                        previous = next.notification().sequenceNumber();
                    }
                } catch (OpcUaClient.ServiceException e) {
                    break;
                }
            }
        }
    }

    @Test
    void republishRetrievesNotificationWithinWindow() throws Exception {
        try (SessionClient session = newSession()) {
            long subscriptionId = createSubscription(session, 50, 60_000);
            createMonitoredItem(session, subscriptionId, NodeId.numeric(2001), 10, 50);
            // Produce notifications with nobody publishing: they get retained.
            writeValue(session, NodeId.numeric(2001), 1);
            Thread.sleep(120);
            writeValue(session, NodeId.numeric(2001), 2);
            Thread.sleep(120);

            // A publish after the gap drains the oldest retained notification first.
            ServicePayloads.PublishResponse delivered = session.client.publish(
                    new ServicePayloads.PublishRequest(session.token, subscriptionId, 3_000));
            long firstSequence = delivered.notification().sequenceNumber();

            // Drain until at least one later notification exists, then republish the first.
            ServicePayloads.PublishResponse second = null;
            for (int i = 0; i < 10 && second == null; i++) {
                try {
                    ServicePayloads.PublishResponse candidate = session.client.publish(
                            new ServicePayloads.PublishRequest(session.token, subscriptionId, 500));
                    if (!candidate.notification().keepAlive()) {
                        second = candidate;
                    }
                } catch (OpcUaClient.ServiceException e) {
                    break;
                }
            }
            // Republish the first sequence: it must still be in the window.
            Response republish = session.client.exchange(RequestType.REPUBLISH,
                    new ServicePayloads.RepublishRequest(session.token, subscriptionId, firstSequence));
            assertEquals(StatusCode.GOOD.code(), republish.statusCode());
            ServicePayloads.RepublishResponse republished = session.client.objectMapper()
                    .treeToValue(republish.payload(), ServicePayloads.RepublishResponse.class);
            assertEquals(firstSequence, republished.notification().sequenceNumber());
        }
    }

    @Test
    void expiredRetentionIsReclaimedAndRepublishRejected() throws Exception {
        try (SessionClient session = newSession()) {
            long subscriptionId = createSubscription(session, 40, 150);
            createMonitoredItem(session, subscriptionId, NodeId.numeric(2001), 10, 50);
            writeValue(session, NodeId.numeric(2001), 7);
            Thread.sleep(100);
            ServicePayloads.PublishResponse delivered = session.client.publish(
                    new ServicePayloads.PublishRequest(session.token, subscriptionId, 2_000));
            long sequence = delivered.notification().sequenceNumber();
            // Wait past the retention window.
            Thread.sleep(400);
            Response republish = session.client.exchange(RequestType.REPUBLISH,
                    new ServicePayloads.RepublishRequest(session.token, subscriptionId, sequence));
            assertEquals(StatusCode.BAD_MESSAGE_NOT_AVAILABLE.code(), republish.statusCode());
        }
    }

    @Test
    void unpublishCancelsAndReleasesResources() throws Exception {
        try (SessionClient session = newSession()) {
            long subscriptionId = createSubscription(session, 50, 60_000);
            createMonitoredItem(session, subscriptionId, NodeId.numeric(2001), 10, 20);
            int pendingBefore = stats.pendingPublishCount();
            Thread slow = new Thread(() -> {
                try {
                    session.client.publish(new ServicePayloads.PublishRequest(
                            session.token, subscriptionId, 30_000));
                } catch (IOException e) {
                    // expected after unpublish
                }
            });
            slow.start();
            long deadline = System.currentTimeMillis() + 2000;
            while (stats.pendingPublishCount() < pendingBefore + 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(stats.pendingPublishCount() >= pendingBefore + 1,
                    "a pending publish must be counted while slow consumer waits");

            Response unpublish = session.client.exchange(RequestType.UNPUBLISH,
                    new ServicePayloads.UnpublishRequest(session.token, subscriptionId));
            assertEquals(0L, unpublish.statusCode());
            slow.join(3_000);
            assertEquals(pendingBefore, stats.pendingPublishCount());

            Response deleted = session.client.exchange(RequestType.DELETE_SUBSCRIPTION,
                    new ServicePayloads.DeleteSubscriptionRequest(session.token, subscriptionId));
            assertEquals(0L, deleted.statusCode());
            Thread.sleep(100);
            Response again = session.client.exchange(RequestType.DELETE_SUBSCRIPTION,
                    new ServicePayloads.DeleteSubscriptionRequest(session.token, subscriptionId));
            assertEquals(StatusCode.BAD_SUBSCRIPTION_ID_INVALID.code(), again.statusCode());
        }
    }

    @Test
    void monitoredItemAndSubscriptionLimitsReturnDistinctCodes() throws Exception {
        try (SessionClient session = newSession()) {
            long subscriptionId = createSubscription(session, 50, 60_000);
            long first = createMonitoredItem(session, subscriptionId, NodeId.numeric(2001), 10, 10);
            assertTrue(first > 0);
            // Exceed monitored item limit.
            Response overflow = session.client.exchange(RequestType.CREATE_MONITORED_ITEMS,
                    new ServicePayloads.CreateMonitoredItemsRequest(session.token, subscriptionId,
                            List.of(
                                    new ServicePayloads.MonitoredItemCreateRequest(
                                            NodeId.numeric(2001), 10, 10, DiscardPolicy.DISCARD_OLDEST),
                                    new ServicePayloads.MonitoredItemCreateRequest(
                                            NodeId.numeric(2002), 10, 10, DiscardPolicy.DISCARD_OLDEST),
                                    new ServicePayloads.MonitoredItemCreateRequest(
                                            NodeId.numeric(2003), 10, 10, DiscardPolicy.DISCARD_OLDEST),
                                    new ServicePayloads.MonitoredItemCreateRequest(
                                            NodeId.numeric(2004), 10, 10, DiscardPolicy.DISCARD_OLDEST),
                                    new ServicePayloads.MonitoredItemCreateRequest(
                                            NodeId.numeric(2004), 10, 10, DiscardPolicy.DISCARD_OLDEST),
                                    new ServicePayloads.MonitoredItemCreateRequest(
                                            NodeId.numeric(2004), 10, 10, DiscardPolicy.DISCARD_OLDEST))));
            ServicePayloads.CreateMonitoredItemsResponse results = session.client.objectMapper()
                    .treeToValue(overflow.payload(),
                            ServicePayloads.CreateMonitoredItemsResponse.class);
            boolean sawLimit = results.results().stream()
                    .anyMatch(r -> r.statusCode() == StatusCode.BAD_TOO_MANY_MONITORED_ITEMS.code());
            assertTrue(sawLimit);

            // Exceed subscriptions-per-session limit (one already exists).
            createSubscription(session, 50, 60_000);
            createSubscription(session, 50, 60_000);
            Response tooManySubs = session.client.exchange(RequestType.CREATE_SUBSCRIPTION,
                    new ServicePayloads.CreateSubscriptionRequest(session.token, 50, 0, 10, 1, 60_000));
            assertTrue(tooManySubs.statusCode() != 0L);
        }
    }
}
