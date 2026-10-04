package com.github.guoyyit.opcuaserverjava.opcua.model;

/**
 * Identifier of a node in the address space (ns="ns";i= numeric ids).
 */
public record NodeId(int namespace, long identifier) {

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NodeId other)) {
            return false;
        }
        return namespace == other.namespace && identifier == other.identifier;
    }

    @Override
    public int hashCode() {
        return 31 * namespace + Long.hashCode(identifier);
    }

    public static NodeId numeric(long identifier) {
        return new NodeId(0, identifier);
    }

    public static NodeId parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty node id");
        }
        int namespace = 0;
        String rest = text;
        if (text.startsWith("ns=")) {
            int sep = text.indexOf(';');
            if (sep < 0) {
                throw new IllegalArgumentException("invalid node id: " + text);
            }
            namespace = Integer.parseInt(text.substring(3, sep));
            rest = text.substring(sep + 1);
        }
        if (!rest.startsWith("i=")) {
            throw new IllegalArgumentException("only numeric node ids are supported: " + text);
        }
        long identifier = Long.parseLong(rest.substring(2));
        return new NodeId(namespace, identifier);
    }

    @Override
    public String toString() {
        return "ns=" + namespace + ";i=" + identifier;
    }
}
