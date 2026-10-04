package com.github.guoyyit.opcuaserverjava.opcua.core;

/**
 * 节点标识（简化版，使用字符串命名空间+字符串标识）。
 */
public record NodeId(String namespaceUri, String identifier) {

    public NodeId {
        if (namespaceUri == null || identifier == null) {
            throw new OpcUaException(com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode.BAD_NODE_ID_UNKNOWN,
                    "NodeId 不允许为空");
        }
    }

    public static NodeId of(String identifier) {
        return new NodeId("urn:opcua-server-java:demo", identifier);
    }

    @Override
    public String toString() {
        return "ns=" + namespaceUri + ";i=" + identifier;
    }
}
