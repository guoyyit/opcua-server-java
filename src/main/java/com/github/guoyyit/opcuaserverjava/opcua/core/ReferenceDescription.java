package com.github.guoyyit.opcuaserverjava.opcua.core;

/**
 * 浏览返回的引用描述。
 */
public record ReferenceDescription(NodeId sourceNodeId,
                                   String referenceTypeId,
                                   boolean forward,
                                   NodeId targetNodeId,
                                   String displayName,
                                   NodeClass targetNodeClass) {
}
