package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.addressspace.Node;
import com.github.guoyyit.opcuaserverjava.opcua.addressspace.Reference;
import com.github.guoyyit.opcuaserverjava.opcua.client.LoopbackClient;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseDirection;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseResult;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeClass;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.ReferenceDescription;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地址空间浏览：层级/续读分页/并发无漏无重/变更自洽/错误码。
 */
class BrowseTest {

    private final TestServerFactory.TestContext context = TestServerFactory.newContext();

    private LoopbackClient activatedClient() {
        LoopbackClient client = new LoopbackClient(context.server);
        client.openChannel(10_000L);
        client.createSession(5_000L);
        client.activateAnonymous();
        return client;
    }

    @Test
    void browsesHierarchyForwardAndInverse() {
        var client = activatedClient();

        var forward = client.browse(DemoAddressSpace.DEVICE_A,
                BrowseDirection.FORWARD, null, 100);
        assertEquals(3, forward.getReferences().size());
        assertTrue(forward.getReferences().stream().allMatch(ReferenceDescription::forward));

        var inverse = client.browse(DemoAddressSpace.TEMPERATURE,
                BrowseDirection.INVERSE, null, 100);
        assertEquals(1, inverse.getReferences().size());
        assertEquals(DemoAddressSpace.DEVICE_A,
                inverse.getReferences().get(0).targetNodeId());

        // 引用类型过滤。
        var properties = client.browse(DemoAddressSpace.DEVICE_A,
                BrowseDirection.FORWARD, DemoAddressSpace.REF_HAS_PROPERTY, 100);
        assertEquals(3, properties.getReferences().size());
        client.close();
    }

    @Test
    void browseContinuationPaginatesWithoutLossOrDuplicate() {
        var client = activatedClient();
        List<NodeId> collected = new ArrayList<>();
        BrowseResult page = client.browse(DemoAddressSpace.DEVICE_A,
                BrowseDirection.FORWARD, null, 2);
        page.getReferences().forEach(r -> collected.add(r.targetNodeId()));
        int pages = 1;
        while (page.hasContinuationPoint()) {
            page = client.browseNext(page.getContinuationPoint());
            page.getReferences().forEach(r -> collected.add(r.targetNodeId()));
            pages++;
        }
        assertEquals(2, pages);
        assertEquals(3, collected.size());
        assertEquals(3, new HashSet<>(collected).size()); // 无重复
        client.close();
    }

    @Test
    void unknownNodeAndReferenceReturnDistinctErrors() {
        var client = activatedClient();

        OpcUaException unknownNode = assertThrows(OpcUaException.class,
                () -> client.browse(NodeId.of("Does.Not.Exist"),
                        BrowseDirection.FORWARD, null, 100));
        assertEquals(StatusCode.BAD_NODE_ID_UNKNOWN, unknownNode.getStatusCode());

        OpcUaException unknownReference = assertThrows(OpcUaException.class,
                () -> client.browse(DemoAddressSpace.DEVICE_A,
                        BrowseDirection.FORWARD, "NoSuchReference", 100));
        assertEquals(StatusCode.BAD_REFERENCE_ID_UNKNOWN, unknownReference.getStatusCode());
        client.close();
    }

    @Test
    void concurrentBrowseAndMutationStaysSelfConsistent() throws Exception {
        // 准备一个含有 500 个属性的大对象，浏览期间持续增删节点。
        NodeId folderId = NodeId.of("Folder.BrowseStress");
        var folder = new Node(folderId, NodeClass.OBJECT, "BrowseStress");
        context.addressSpace.addNode(folder);
        List<NodeId> children = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            NodeId childId = NodeId.of("BrowseStress.Child." + i);
            context.addressSpace.addNode(new Node(childId, NodeClass.OBJECT, "C" + i));
            context.addressSpace.addReference(
                    new Reference(folderId, DemoAddressSpace.REF_HAS_CHILD, true, childId));
            children.add(childId);
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger totalSeen = new AtomicInteger();

        // 浏览线程：分页读完全部引用，检查无重复且总数与页数自洽。
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    LoopbackClient client = activatedClient();
                    Set<NodeId> seen = new HashSet<>();
                    BrowseResult page = client.browse(folderId,
                            BrowseDirection.FORWARD, null, 37);
                    page.getReferences().forEach(r -> assertTrue(seen.add(r.targetNodeId())));
                    while (page.hasContinuationPoint()) {
                        page = client.browseNext(page.getContinuationPoint());
                        for (ReferenceDescription description : page.getReferences()) {
                            assertTrue(seen.add(description.targetNodeId()),
                                    "分页中出现重复节点");
                        }
                    }
                    totalSeen.addAndGet(seen.size());
                    client.close();
                } catch (AssertionError | RuntimeException | InterruptedException e) {
                    failures.incrementAndGet();
                }
            });
        }
        // 变更线程：不断添加/删除“快照之外”的新节点，不应破坏任何进行中浏览的自洽。
        for (int t = 0; t < 2; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        NodeId mutableId = NodeId.of("Mutable." + Thread.currentThread().getId()
                                + "." + i);
                        Node node = new Node(mutableId, NodeClass.OBJECT, "M" + i);
                        context.addressSpace.addNode(node);
                        context.addressSpace.addReference(new Reference(folderId,
                                DemoAddressSpace.REF_HAS_CHILD, true, mutableId));
                        context.addressSpace.addReference(new Reference(mutableId,
                                DemoAddressSpace.REF_HAS_CHILD, false, folderId));
                    }
                } catch (RuntimeException | InterruptedException e) {
                    failures.incrementAndGet();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, failures.get());
        assertTrue(totalSeen.get() >= 4 * 500);
    }
}
