package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.model.AccessLevel;
import com.github.guoyyit.opcuaserverjava.opcua.model.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeClass;
import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Mutable address space node. The current value is guarded by a lock so that
 * every read observes one complete written value.
 */
public final class Node {

    private final NodeId nodeId;
    private final NodeClass nodeClass;
    private final String browseName;
    private final String displayName;
    private final Object valueLock = new Object();
    private final Set<AccessLevel> accessLevels;
    private DataValue dataValue;

    public Node(NodeId nodeId,
                NodeClass nodeClass,
                String browseName,
                String displayName,
                DataValue dataValue,
                Set<AccessLevel> accessLevels) {
        this.nodeId = nodeId;
        this.nodeClass = nodeClass;
        this.browseName = browseName;
        this.displayName = displayName;
        this.dataValue = dataValue;
        this.accessLevels = accessLevels == null
                ? EnumSet.noneOf(AccessLevel.class)
                : EnumSet.copyOf(accessLevels);
    }

    public NodeId nodeId() {
        return nodeId;
    }

    public NodeClass nodeClass() {
        return nodeClass;
    }

    public String browseName() {
        return browseName;
    }

    public String displayName() {
        return displayName;
    }

    public DataValue dataValue() {
        synchronized (valueLock) {
            return dataValue;
        }
    }

    public void dataValue(DataValue value) {
        synchronized (valueLock) {
            this.dataValue = value;
        }
    }

    public Set<AccessLevel> accessLevels() {
        return Collections.unmodifiableSet(accessLevels);
    }
}
