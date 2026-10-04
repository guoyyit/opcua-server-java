package com.github.guoyyit.opcuaserverjava.opcua.security;

import com.github.guoyyit.opcuaserverjava.opcua.core.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.core.UserIdentityToken;
import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内置的用户名/口令校验器（内存账户，不依赖任何外部服务）。
 */
public class SimpleIdentityValidator implements IdentityValidator {

    private record StoredAccount(String password, Set<String> roles) {
    }

    private final Map<String, StoredAccount> accounts = new ConcurrentHashMap<>();

    public SimpleIdentityValidator() {
        accounts.put("operator",
                new StoredAccount("operator-pass", Set.of("ROLE_OPERATOR")));
        accounts.put("admin",
                new StoredAccount("admin-pass", Set.of("ROLE_OPERATOR", "ROLE_ADMIN")));
    }

    @Override
    public UserIdentity validate(UserIdentityToken token) {
        if (token == null || token.getType() == null) {
            throw new OpcUaException(StatusCode.BAD_IDENTITY_TOKEN_INVALID);
        }
        if (token.getType() == UserIdentityToken.Type.ANONYMOUS) {
            return UserIdentity.anonymous();
        }
        String userName = token.getUserName();
        String password = token.getPassword();
        if (userName == null || userName.isBlank() || password == null) {
            // 用户不存在与口令错误统一为 REJECTED，避免泄露用户是否存在。
            throw new OpcUaException(StatusCode.BAD_IDENTITY_TOKEN_REJECTED,
                    "身份凭据校验失败");
        }
        StoredAccount account = accounts.get(userName);
        boolean matched = account != null && constantTimeEquals(account.password, password);
        if (!matched) {
            throw new OpcUaException(StatusCode.BAD_IDENTITY_TOKEN_REJECTED,
                    "身份凭据校验失败");
        }
        return new UserIdentity(userName, false, account.roles);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int result = expected.length() ^ actual.length();
        int length = Math.max(expected.length(), actual.length());
        for (int i = 0; i < length; i++) {
            int left = i < expected.length() ? expected.charAt(i) : 0;
            int right = i < actual.length() ? actual.charAt(i) : 0;
            result |= left ^ right;
        }
        return result == 0;
    }
}
