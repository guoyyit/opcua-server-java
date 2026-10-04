package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.model.NodeId;

/**
 * Directed reference between two nodes.
 */
public record Reference(NodeId sourceId, NodeId referenceTypeId, NodeId targetId, boolean isForward) {
}
