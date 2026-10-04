package com.github.guoyyit.opcuaserverjava.opcua.security;

import java.util.Set;

/**
 * 认证通过后的用户身份与权限集合。
 */
public final class UserIdentity {

    private final String userName;
    private final boolean anonymous;
    private final Set<String> roles;

    public UserIdentity(String userName, boolean anonymous, Set<String> roles) {
        this.userName = userName;
        this.anonymous = anonymous;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static UserIdentity anonymous() {
        return new UserIdentity("anonymous", true, Set.of("ROLE_ANONYMOUS"));
    }

    public String getUserName() {
        return userName;
    }

    public boolean isAnonymous() {
        return anonymous;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public boolean canWrite() {
        return !anonymous;
    }
}
