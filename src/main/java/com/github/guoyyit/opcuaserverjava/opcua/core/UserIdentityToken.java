package com.github.guoyyit.opcuaserverjava.opcua.core;

/**
 * 激活会话时提交的身份令牌。
 * type=ANONYMOUS 时忽略用户名口令；
 * type=USERNAME 时携带用户名与口令，由服务端凭据校验器核对。
 */
public final class UserIdentityToken {

    public enum Type { ANONYMOUS, USERNAME }

    private final Type type;
    private final String userName;
    private final String password;

    private UserIdentityToken(Type type, String userName, String password) {
        this.type = type;
        this.userName = userName;
        this.password = password;
    }

    public static UserIdentityToken anonymous() {
        return new UserIdentityToken(Type.ANONYMOUS, null, null);
    }

    public static UserIdentityToken username(String userName, String password) {
        return new UserIdentityToken(Type.USERNAME, userName, password);
    }

    public Type getType() {
        return type;
    }

    public String getUserName() {
        return userName;
    }

    public String getPassword() {
        return password;
    }
}
