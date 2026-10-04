package com.github.guoyyit.opcuaserverjava.tests;

import com.github.guoyyit.opcuaserverjava.opcua.addressspace.DemoAddressSpace;
import com.github.guoyyit.opcuaserverjava.opcua.client.LoopbackClient;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读写：时间戳与状态码、原子可见性、错误码区分、并发读写。
 */
class ReadWriteTest {

    private final TestServerFactory.TestContext context = TestServerFactory.newContext();

    private LoopbackClient operatorClient() {
        LoopbackClient client = new LoopbackClient(context.server);
        client.openChannel(10_000L);
        client.createSession(5_000L);
        client.activateUser("operator", "operator-pass");
        return client;
    }

    @Test
    void readCarriesSourceTimestampAndStatus() {
        var client = operatorClient();
        long before = System.currentTimeMillis();
        client.write(DemoAddressSpace.SET_POINT, 30);
        long after = System.currentTimeMillis();

        DataValue value = client.read(DemoAddressSpace.SET_POINT);
        assertEquals(StatusCode.GOOD, value.getStatusCode());
        assertEquals(30, value.getValue());
        assertTrue(value.getSourceTimestamp() >= before - 1
                && value.getSourceTimestamp() <= after + 20);
        assertTrue(value.getServerTimestamp() > 0);
        client.close();
    }

    @Test
    void writeErrorsAreDistinguished() {
        var client = operatorClient();

        // 不存在的节点。
        assertBad(StatusCode.BAD_NODE_ID_UNKNOWN,
                () -> client.write(NodeId.of("Missing.Node"), 1));

        // 类型不匹配：SetPoint 是 Integer。
        assertBad(StatusCode.BAD_TYPE_MISMATCH,
                () -> client.write(DemoAddressSpace.SET_POINT, "not-an-int"));
        assertBad(StatusCode.BAD_TYPE_MISMATCH,
                () -> client.write(DemoAddressSpace.SET_POINT, 1.5d));

        // 读取不存在节点也返回同一类明确错误码。
        assertBad(StatusCode.BAD_NODE_ID_UNKNOWN,
                () -> client.read(NodeId.of("Missing.Node")));
        client.close();

        // 权限不足（匿名写）与节点访问级别（只读）是两个可区分错误码。
        var anonymous = new LoopbackClient(context.server);
        anonymous.openChannel(10_000L);
        anonymous.createSession(5_000L);
        anonymous.activateAnonymous();
        assertBad(StatusCode.BAD_USER_ACCESS_DENIED,
                () -> anonymous.write(DemoAddressSpace.TEMPERATURE, 30.0d));
        anonymous.close();
    }

    @Test
    void readOnlyNodeRejectsWriteByAccessLevel() {
        var client = operatorClient();
        // Pressure 为只读节点：凭据合法，但节点访问级别拒绝写入。
        assertBad(StatusCode.BAD_NOT_WRITABLE,
                () -> client.write(DemoAddressSpace.PRESSURE, 50.0d));
        // 只读节点仍可读。
        assertEquals(101.3d, client.read(DemoAddressSpace.PRESSURE).getValue());
        client.close();
    }

    @Test
    void concurrentReadsAlwaysSeeACompleteWrite() throws Exception {
        int writerThreads = 4;
        int readerThreads = 4;
        int writesPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();

        for (int w = 0; w < writerThreads; w++) {
            final int base = w * 100_000;
            pool.submit(() -> {
                LoopbackClient client = operatorClient();
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        client.write(DemoAddressSpace.TEMPERATURE, (double) base + i);
                    }
                } catch (InterruptedException | RuntimeException e) {
                    failures.incrementAndGet();
                } finally {
                    client.close();
                }
            });
        }
        for (int r = 0; r < readerThreads; r++) {
            pool.submit(() -> {
                LoopbackClient client = operatorClient();
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        DataValue value = client.read(DemoAddressSpace.TEMPERATURE);
                        Object raw = value.getValue();
                        // 任一读取必须是某个完整写入：类型正确、状态 GOOD、时间戳为正。
                        if (!(raw instanceof Double)
                                || value.getStatusCode() != StatusCode.GOOD
                                || value.getSourceTimestamp() <= 0) {
                            failures.incrementAndGet();
                        }
                        reads.incrementAndGet();
                    }
                } catch (InterruptedException | RuntimeException e) {
                    failures.incrementAndGet();
                } finally {
                    client.close();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get(), "并发读写期间出现非完整写入或错误状态");
        assertTrue(reads.get() > 0);
    }

    private static void assertBad(StatusCode expected, Runnable action) {
        OpcUaException exception = assertThrows(OpcUaException.class, action::run);
        assertEquals(expected, exception.getStatusCode());
    }
}
