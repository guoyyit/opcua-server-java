package com.github.guoyyit.opcuaserverjava.opcua.addressspace;

import com.github.guoyyit.opcuaserverjava.opcua.core.NodeId;

/**
 * 有向引用。forward=false 表示逆引用（HasModelParent 等浏览逆向视图）。
 */
public record Reference(NodeId sourceId,
                        String referenceTypeId,
                        boolean forward,
                        NodeId targetId) {
}
