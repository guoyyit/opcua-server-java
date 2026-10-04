package com.github.guoyyit.opcuaserverjava.opcua.addressspace;

import com.github.guoyyit.opcuaserverjava.opcua.core.AccessLevel;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseDirection;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseResult;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeClass;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;
import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.ReferenceDescription;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存地址空间。
 *
 * <p>浏览：在调用瞬间对引用做不可变快照，排序后分页；续读通过不透明 continuationPoint
 * 继续同一份快照，因此浏览期间节点集即使变更，结果也不漏、不重且自洽。
 * 节点值：写入以“整体替换不可变 {@link DataValue}”方式发布，读到的总是某次完整写入。
 */
public class InMemoryAddressSpace implements AddressSpace {

    private final ConcurrentMap<NodeId, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentMap<NodeId, List<Reference>> referencesBySource = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Long, BrowsePage> continuationPages = new ConcurrentSkipListMap<>();
    private final AtomicLong continuationIdSequence = new AtomicLong();

    @Override
    public void addNode(Node node) {
        nodes.put(node.getNodeId(), node);
        referencesBySource.computeIfAbsent(node.getNodeId(), k -> new ArrayList<>());
    }

    @Override
    public void addReference(Reference reference) {
        if (!nodes.containsKey(reference.sourceId())) {
            throw new OpcUaException(StatusCode.BAD_NODE_ID_UNKNOWN,
                    "引用源节点不存在: " + reference.sourceId());
        }
        if (!nodes.containsKey(reference.targetId())) {
            throw new OpcUaException(StatusCode.BAD_NODE_ID_UNKNOWN,
                    "引用目标节点不存在: " + reference.targetId());
        }
        referencesBySource.compute(reference.sourceId(), (key, list) -> {
            List<Reference> updated = list == null ? new ArrayList<>() : new ArrayList<>(list);
            updated.add(reference);
            return List.copyOf(updated);
        });
        // 逆引用视图也落到目标节点上，保持单向存储、双向浏览。
        Reference inverse = new Reference(reference.targetId(), reference.referenceTypeId(),
                false, reference.sourceId());
        referencesBySource.compute(inverse.sourceId(), (key, list) -> {
            List<Reference> updated = list == null ? new ArrayList<>() : new ArrayList<>(list);
            updated.add(inverse);
            return List.copyOf(updated);
        });
    }

    @Override
    public Node getNode(NodeId nodeId) {
        return nodes.get(nodeId);
    }

    @Override
    public Node requireNode(NodeId nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new OpcUaException(StatusCode.BAD_NODE_ID_UNKNOWN);
        }
        return node;
    }

    @Override
    public BrowseResult browse(NodeId nodeId, BrowseDirection direction,
                               String referenceTypeId, int maxReferencesPerNode,
        byte[] continuationPoint) {
        if (continuationPoint != null && continuationPoint.length > 0) {
            return continueBrowse(continuationPoint, maxReferencesPerNode);
        }
        requireNode(nodeId);
        List<Reference> snapshot = referencesBySource.getOrDefault(nodeId, List.of());

        boolean referenceTypeExists = referenceTypeId == null || isKnownReferenceType(referenceTypeId);
        if (!referenceTypeExists) {
            throw new OpcUaException(StatusCode.BAD_REFERENCE_ID_UNKNOWN);
        }

        List<ReferenceDescription> filtered = new ArrayList<>();
        for (Reference reference : snapshot) {
            if (!matchesDirection(reference.forward(), direction)) {
                continue;
            }
            if (referenceTypeId != null && !referenceTypeId.equals(reference.referenceTypeId())) {
                continue;
            }
            Node target = nodes.get(reference.targetId());
            if (target == null) {
                // 快照中目标已删除则跳过，结果保持自洽（不返回悬空引用）。
                continue;
            }
            filtered.add(new ReferenceDescription(nodeId, reference.referenceTypeId(),
                    reference.forward(), reference.targetId(),
                    target.getDisplayName(), target.getNodeClass()));
        }
        filtered.sort(Comparator
                .comparing((ReferenceDescription r) -> r.referenceTypeId())
                .thenComparing(r -> r.targetNodeId().identifier()));

        long pageId = continuationIdSequence.incrementAndGet();
        continuationPages.put(pageId, new BrowsePage(pageId, filtered));
        return pageResult(pageId, maxReferencesPerNode <= 0 ? filtered.size() : maxReferencesPerNode);
    }

    @Override
    public void releaseContinuationPoint(byte[] continuationPoint) {
        continuationPages.remove(decodePageId(continuationPoint));
    }

    @Override
    public DataValue read(NodeId nodeId) {
        Node node = requireNode(nodeId);
        if (node.getAccessLevel() == AccessLevel.NONE || node.getAccessLevel() == AccessLevel.WRITE) {
            throw new OpcUaException(StatusCode.BAD_NOT_READABLE);
        }
        DataValue value = node.getDataValue();
        if (value == null) {
            throw new OpcUaException(StatusCode.BAD_INTERNAL_ERROR, "节点尚无值");
        }
        return value;
    }

    @Override
    public synchronized void write(NodeId nodeId, Object value) {
        Node node = requireNode(nodeId);
        if (node.getAccessLevel() == AccessLevel.NONE || node.getAccessLevel() == AccessLevel.READ) {
            throw new OpcUaException(StatusCode.BAD_NOT_WRITABLE);
        }
        if (node.getNodeClass() != NodeClass.VARIABLE) {
            throw new OpcUaException(StatusCode.BAD_NOT_WRITABLE);
        }
        if (node.getDataType() != null && value != null
                && !isAssignable(node.getDataType(), value.getClass())) {
            throw new OpcUaException(StatusCode.BAD_TYPE_MISMATCH);
        }
        long now = System.currentTimeMillis();
        node.setDataValue(new DataValue(value, StatusCode.GOOD, now, now));
    }

    @Override
    public List<Node> snapshot() {
        return List.copyOf(nodes.values());
    }

    private BrowseResult continueBrowse(byte[] continuationPoint, int pageSize) {
        long pageId = decodePageId(continuationPoint);
        BrowsePage page = continuationPages.get(pageId);
        if (page == null) {
            throw new OpcUaException(StatusCode.BAD_INTERNAL_ERROR, "浏览续读点已失效");
        }
        return pageResult(pageId, pageSize <= 0 ? page.references.size() : pageSize);
    }

    private BrowseResult pageResult(long pageId, int pageSize) {
        BrowsePage page = continuationPages.get(pageId);
        int from = page.nextIndex;
        int to = Math.min(from + pageSize, page.references.size());
        List<ReferenceDescription> slice = List.copyOf(page.references.subList(from, to));
        page.nextIndex = to;
        byte[] nextPoint = to < page.references.size() ? encodePageId(pageId) : new byte[0];
        if (nextPoint.length == 0) {
            continuationPages.remove(pageId);
        }
        return new BrowseResult(slice, nextPoint);
    }

    private boolean isKnownReferenceType(String referenceTypeId) {
        // 引用类型通过地址空间中是否出现该类型标识判定；
        // 演示节点集会注册 HasChild / Organizes / HasProperty 等。
        for (List<Reference> references : referencesBySource.values()) {
            for (Reference reference : references) {
                if (referenceTypeId.equals(reference.referenceTypeId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesDirection(boolean forward, BrowseDirection direction) {
        return switch (direction) {
            case FORWARD -> forward;
            case INVERSE -> !forward;
            case BOTH -> true;
        };
    }

    private static boolean isAssignable(Class<?> declared, Class<?> actual) {
        if (declared.isPrimitive()) {
            return wrapperOf(declared).isAssignableFrom(actual);
        }
        return declared.isAssignableFrom(actual);
    }

    private static Class<?> wrapperOf(Class<?> primitive) {
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        return primitive;
    }

    private static byte[] encodePageId(long pageId) {
        return ByteBuffer.allocate(Long.BYTES).putLong(pageId).array();
    }

    private static long decodePageId(byte[] bytes) {
        if (bytes == null || bytes.length != Long.BYTES) {
            throw new OpcUaException(StatusCode.BAD_INTERNAL_ERROR, "非法浏览续读点");
        }
        return ByteBuffer.wrap(bytes).getLong();
    }

    private static final class BrowsePage {
        private final long id;
        private final List<ReferenceDescription> references;
        private volatile int nextIndex;

        private BrowsePage(long id, List<ReferenceDescription> references) {
            this.id = id;
            this.references = references;
        }
    }
}
