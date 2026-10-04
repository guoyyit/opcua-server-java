package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.model.AccessLevel;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeClass;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hierarchical in-memory address space used for local loopback validation.
 *
 * <p>Nodes live in a concurrent map; forward references of each node live in
 * an append/removal safe list. Browse builds a sorted stable snapshot, so a
 * concurrent Browse + BrowseNext never misses or duplicates a node and stays
 * self-consistent even when the node set changes while paging.
 */
@Component
public class AddressSpace {

    private static final NodeId ORGANIZES = NodeId.numeric(35);

    private final ConcurrentMap<NodeId, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentMap<NodeId, CopyOnWriteArrayList<Reference>> forwardReferences =
            new ConcurrentHashMap<>();

    public AddressSpace() {
        seed();
    }

    private void seed() {
        addNode(new Node(NodeId.numeric(84), NodeClass.OBJECT, "Root", "Root", null,
                EnumSet.noneOf(AccessLevel.class)));
        addNode(new Node(NodeId.numeric(85), NodeClass.OBJECT, "Objects", "Objects", null,
                EnumSet.noneOf(AccessLevel.class)));
        organize(NodeId.numeric(84), NodeId.numeric(85));

        addNode(new Node(NodeId.numeric(2000), NodeClass.OBJECT, "Device", "Local Device", null,
                EnumSet.noneOf(AccessLevel.class)));
        organize(NodeId.numeric(85), NodeId.numeric(2000));

        addNode(new Node(NodeId.numeric(2001), NodeClass.VARIABLE, "Temperature", "Temperature",
                com.github.guoyyit.opcuaserverjava.opcua.model.DataValue.good(23.5d),
                EnumSet.of(AccessLevel.CURRENT_READ, AccessLevel.CURRENT_WRITE)));
        organize(NodeId.numeric(2000), NodeId.numeric(2001));

        addNode(new Node(NodeId.numeric(2002), NodeClass.VARIABLE, "Pressure", "Pressure",
                com.github.guoyyit.opcuaserverjava.opcua.model.DataValue.good(101L),
                EnumSet.of(AccessLevel.CURRENT_READ)));
        organize(NodeId.numeric(2000), NodeId.numeric(2002));

        addNode(new Node(NodeId.numeric(2003), NodeClass.VARIABLE, "Setpoint", "Setpoint",
                com.github.guoyyit.opcuaserverjava.opcua.model.DataValue.good(20),
                EnumSet.of(AccessLevel.CURRENT_READ, AccessLevel.CURRENT_WRITE)));
        organize(NodeId.numeric(2000), NodeId.numeric(2003));

        addNode(new Node(NodeId.numeric(2004), NodeClass.VARIABLE, "SerialNumber", "Serial Number",
                com.github.guoyyit.opcuaserverjava.opcua.model.DataValue.good("SN-0001"),
                EnumSet.of(AccessLevel.CURRENT_READ)));
        organize(NodeId.numeric(2000), NodeId.numeric(2004));
    }

    public void addNode(Node node) {
        nodes.put(node.nodeId(), node);
        forwardReferences.computeIfAbsent(node.nodeId(), id -> new CopyOnWriteArrayList<>());
    }

    public void removeNode(NodeId nodeId) {
        nodes.remove(nodeId);
        forwardReferences.remove(nodeId);
        for (CopyOnWriteArrayList<Reference> refs : forwardReferences.values()) {
            refs.removeIf(reference -> reference.targetId().equals(nodeId));
        }
    }

    public void organize(NodeId parent, NodeId child) {
        if (!nodes.containsKey(parent) || !nodes.containsKey(child)) {
            throw new OpcUaException(StatusCode.BAD_NODE_ID_UNKNOWN);
        }
        CopyOnWriteArrayList<Reference> refs =
                forwardReferences.computeIfAbsent(parent, id -> new CopyOnWriteArrayList<>());
        Reference reference = new Reference(parent, ORGANIZES, child, true);
        boolean exists = refs.stream().anyMatch(existing -> existing.targetId().equals(child));
        if (!exists) {
            refs.add(reference);
        }
    }

    public Node requireNode(NodeId nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new OpcUaException(StatusCode.BAD_NODE_ID_UNKNOWN);
        }
        return node;
    }

    /**
     * Returns a sorted snapshot of the target nodes currently referenced by the
     * given node. Sorting by target id gives every concurrent pager the same
     * deterministic order without holding a lock on the address space.
     */
    public List<Node> browseSnapshot(NodeId nodeId) {
        requireNode(nodeId);
        CopyOnWriteArrayList<Reference> refs = forwardReferences.get(nodeId);
        if (refs == null) {
            return List.of();
        }
        NavigableMap<NodeId, Node> sorted = new ConcurrentSkipListMap<>(
                Comparator.comparingInt(NodeId::namespace).thenComparingLong(NodeId::identifier));
        for (Reference reference : refs) {
            Node target = nodes.get(reference.targetId());
            if (target != null) {
                sorted.put(target.nodeId(), target);
            }
        }
        return new ArrayList<>(sorted.values());
    }

    public List<Reference> browse(NodeId nodeId) {
        requireNode(nodeId);
        CopyOnWriteArrayList<Reference> refs = forwardReferences.get(nodeId);
        if (refs == null) {
            return List.of();
        }
        return List.copyOf(refs);
    }

    public int nodeCount() {
        return nodes.size();
    }
}
