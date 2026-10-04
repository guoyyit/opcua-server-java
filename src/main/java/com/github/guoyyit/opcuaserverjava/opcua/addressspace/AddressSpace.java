package com.github.guoyyit.opcuaserverjava.opcua.addressspace;

import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseDirection;
import com.github.guoyyit.opcuaserverjava.opcua.core.BrowseResult;
import com.github.guoyyit.opcuaserverjava.opcua.core.DataValue;
import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;

import java.util.List;

/**
 * 线程安全的地址空间：浏览基于快照，节点值通过不可变 DataValue 原子发布。
 */
public interface AddressSpace {

    void addNode(Node node);

    void addReference(Reference reference);

    Node getNode(NodeId nodeId);

    Node requireNode(NodeId nodeId);

    BrowseResult browse(NodeId nodeId,
                        BrowseDirection direction,
                        String referenceTypeId,
                        int maxReferencesPerNode,
                        byte[] continuationPoint);

    void releaseContinuationPoint(byte[] continuationPoint);

    DataValue read(NodeId nodeId);

    void write(NodeId nodeId, Object value);

    List<Node> snapshot();
}
