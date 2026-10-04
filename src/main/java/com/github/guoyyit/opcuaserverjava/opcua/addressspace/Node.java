package com.github.guoyyit.opcuaserverjava.opcua.addressspace;

import com.github.guoyyit.opcuaserverjava.opcua.core.AccessLevel;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeClass;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;

/**
 * 地址空间节点。节点值与元数据分开加锁，
 * 值通过不可变 {@link DataValue} 发布，保证并发读到完整值。
 */
public class Node {

    private final NodeId nodeId;
    private final NodeClass nodeClass;
    private volatile String displayName;
    private volatile Class<?> dataType;
    private volatile AccessLevel accessLevel;
    private volatile DataValue dataValue;

    public Node(NodeId nodeId, NodeClass nodeClass, String displayName) {
        this.nodeId = nodeId;
        this.nodeClass = nodeClass;
        this.displayName = displayName;
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public NodeClass getNodeClass() {
        return nodeClass;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Class<?> getDataType() {
        return dataType;
    }

    public void setDataType(Class<?> dataType) {
        this.dataType = dataType;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public DataValue getDataValue() {
        return dataValue;
    }

    public void setDataValue(DataValue dataValue) {
        this.dataValue = dataValue;
    }
}
