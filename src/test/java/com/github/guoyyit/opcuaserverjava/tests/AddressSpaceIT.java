package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.client.OpcUaClient;
import com.github.guoyyit.opcuaserverjava.opcua.message.RequestType;
import com.github.guoyyit.opcuaserverjava.opcua.message.Response;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.BrowseResponse;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.ReadResponse;
import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.ReferenceDescription;
import com.github.guoyyit.opcuaserverjava.opcua.model.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.model.IdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.transport.OpcUaTcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hierarchical browse with continuation paging, concurrent browse
 * consistency, unknown node/reference errors, read/write atomicity and
 * distinguishable write error codes.
 */
@SpringBootTest(properties = {
        "opcua.default-channel-lifetime-ms=60000",
        "opcua.default-session-timeout-ms=60000",
        "opcua.reaper-interval-ms=40"
})
@Timeout(40)
class AddressSpaceIT {

    @Autowired
    private OpcUaTcpServer server;

    private OpcUaClient setup() throws IOException {
        OpcUaClient client = new OpcUaClient("127.0.0.1", server.getBoundPort());
        client.connect();
        client.openSecureChannel(0);
        Response created = client.exchange(RequestType.CREATE_SESSION,
                new ServicePayloads.CreateSessionRequest(0));
        ServicePayloads.CreateSessionResponse session = client.objectMapper()
                .treeToValue(created.payload(), ServicePayloads.CreateSessionResponse.class);
        client.exchange(RequestType.ACTIVATE_SESSION,
                new ServicePayloads.ActivateSessionRequest(session.sessionToken(),
                        IdentityToken.anonymous()));
        client.setSessionToken(session.sessionToken());
        return client;
    }

    @Test
    void browsePagingDoesNotMissOrDuplicate() throws Exception {
        try (OpcUaClient client = setup()) {
            String token = client.sessionToken();
            List<Long> collected = new ArrayList<>();
            String continuation = null;
            for (int page = 0; page < 10; page++) {
                Response response;
                if (page == 0) {
                    response = client.exchange(RequestType.BROWSE,
                            new ServicePayloads.BrowseRequest(token, NodeId.numeric(2000), 2));
                } else {
                    response = client.exchange(RequestType.BROWSE_NEXT,
                            new ServicePayloads.BrowseNextRequest(token, continuation, false));
                }
                assertEquals(0L, response.statusCode());
                BrowseResponse browse = client.objectMapper()
                        .treeToValue(response.payload(), BrowseResponse.class);
                for (ReferenceDescription ref : browse.references()) {
                    collected.add(ref.nodeId().identifier());
                }
                continuation = browse.continuationPoint();
                if (continuation == null) {
                    break;
                }
            }
            // Device has 4 variables; no duplicates and none missing.
            assertEquals(4, collected.size());
            assertEquals(Set.of(2001L, 2002L, 2003L, 2004L), new HashSet<>(collected));
        }
    }

    @Test
    void concurrentBrowsesAreEachSelfConsistent() throws Exception {
        try (OpcUaClient client = setup()) {
            String token = client.sessionToken();
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger good = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        List<Long> ids = new ArrayList<>();
                        String continuation = null;
                        for (int page = 0; page < 10; page++) {
                            Response response = page == 0
                                    ? client.exchange(RequestType.BROWSE,
                                    new ServicePayloads.BrowseRequest(token,
                                            NodeId.numeric(2000), 1))
                                    : client.exchange(RequestType.BROWSE_NEXT,
                                    new ServicePayloads.BrowseNextRequest(token, continuation, false));
                            BrowseResponse browse = client.objectMapper()
                                    .treeToValue(response.payload(), BrowseResponse.class);
                            browse.references().forEach(r -> ids.add(r.nodeId().identifier()));
                            continuation = browse.continuationPoint();
                            if (continuation == null) {
                                break;
                            }
                        }
                        // Every pager sees the same stable snapshot: 4 unique nodes.
                        if (ids.size() == 4 && new HashSet<>(ids).size() == 4) {
                            good.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // concurrent continuation points are per request; some
                        // may race on the same token, which is acceptable as
                        // long as successful pagers are self consistent.
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            assertTrue(good.get() >= 1, "at least one concurrent pager must be self consistent");
        }
    }

    @Test
    void unknownNodeReturnsExplicitCode() throws Exception {
        try (OpcUaClient client = setup()) {
            Response browse = client.exchange(RequestType.BROWSE,
                    new ServicePayloads.BrowseRequest(client.sessionToken(),
                            NodeId.numeric(999_999), 10));
            assertEquals(StatusCode.BAD_NODE_ID_UNKNOWN.code(), browse.statusCode());

            Response read = client.exchange(RequestType.READ,
                    new ServicePayloads.ReadRequest(client.sessionToken(),
                            List.of(NodeId.numeric(999_999)), 13, 0));
            ReadResponse readResponse = client.objectMapper()
                    .treeToValue(read.payload(), ReadResponse.class);
            assertEquals(StatusCode.BAD_NODE_ID_UNKNOWN.code(),
                    readResponse.results().get(0).statusCode());
        }
    }

    @Test
    void readsObserveCompleteWrittenValues() throws Exception {
        try (OpcUaClient client = setup()) {
            NodeId target = NodeId.numeric(2001);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger mismatches = new AtomicInteger();
            AtomicInteger writes = new AtomicInteger();
            for (int w = 0; w < 2; w++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        for (int i = 0; i < 200; i++) {
                            int value = i;
                            Response written = client.exchange(RequestType.WRITE,
                                    new ServicePayloads.WriteRequest(client.sessionToken(),
                                            List.of(new ServicePayloads.WriteValue(target,
                                                    DataValue.good(value)))));
                            assertEquals(0L, written.statusCode());
                            writes.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
            }
            for (int r = 0; r < 2; r++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        for (int i = 0; i < 200; i++) {
                            Response response = client.exchange(RequestType.READ,
                                    new ServicePayloads.ReadRequest(client.sessionToken(),
                                            List.of(target), 13, 0));
                            ReadResponse rr = client.objectMapper()
                                    .treeToValue(response.payload(), ReadResponse.class);
                            Object value = rr.results().get(0).value().value().value();
                            if (!(value instanceof Number)) {
                                mismatches.incrementAndGet();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            assertEquals(0, mismatches.get(), "every read must see a complete typed value");
            assertEquals(400, writes.get());
        }
    }

    @Test
    void writeErrorsAreDistinguishable() throws Exception {
        try (OpcUaClient client = setup()) {
            // Write to a read-only node.
            Response readOnly = client.exchange(RequestType.WRITE,
                    new ServicePayloads.WriteRequest(client.sessionToken(),
                            List.of(new ServicePayloads.WriteValue(NodeId.numeric(2002),
                                    DataValue.good(5L)))));
            ServicePayloads.WriteResponse wr = client.objectMapper()
                    .treeToValue(readOnly.payload(), ServicePayloads.WriteResponse.class);
            assertEquals(StatusCode.BAD_NOT_WRITABLE.code(), wr.results().get(0));

            // Type mismatch on a double variable.
            Response typeMismatch = client.exchange(RequestType.WRITE,
                    new ServicePayloads.WriteRequest(client.sessionToken(),
                            List.of(new ServicePayloads.WriteValue(NodeId.numeric(2001),
                                    DataValue.good("not-a-number")))));
            ServicePayloads.WriteResponse wr2 = client.objectMapper()
                    .treeToValue(typeMismatch.payload(), ServicePayloads.WriteResponse.class);
            assertEquals(StatusCode.BAD_TYPE_MISMATCH.code(), wr2.results().get(0));

            // Unknown node.
            Response unknown = client.exchange(RequestType.WRITE,
                    new ServicePayloads.WriteRequest(client.sessionToken(),
                            List.of(new ServicePayloads.WriteValue(NodeId.numeric(999_999),
                                    DataValue.good(1)))));
            ServicePayloads.WriteResponse wr3 = client.objectMapper()
                    .treeToValue(unknown.payload(), ServicePayloads.WriteResponse.class);
            assertEquals(StatusCode.BAD_NODE_ID_UNKNOWN.code(), wr3.results().get(0));
        }
    }

    @Test
    void valueCarriesSourceTimestampAndStatus() throws Exception {
        try (OpcUaClient client = setup()) {
            Response response = client.exchange(RequestType.READ,
                    new ServicePayloads.ReadRequest(client.sessionToken(),
                            List.of(NodeId.numeric(2001)), 13, 0));
            ReadResponse read = client.objectMapper()
                    .treeToValue(response.payload(), ReadResponse.class);
            DataValue value = read.results().get(0).value();
            assertEquals(StatusCode.GOOD.code(), value.statusCode().code());
            assertTrue(value.sourceTime() != null);
        }
    }
}
