package com.github.guoyyit.opcuaserverjava.opcua.model;

/**
 * User identity presented during session activation.
 */
public record IdentityToken(String policyId, String userName, String password) {

    public static IdentityToken anonymous() {
        return new IdentityToken("anonymous", null, null);
    }

    public static IdentityToken username(String userName, String password) {
        return new IdentityToken("username", userName, password);
    }

    public boolean isAnonymous() {
        return "anonymous".equalsIgnoreCase(policyId);
    }
}
