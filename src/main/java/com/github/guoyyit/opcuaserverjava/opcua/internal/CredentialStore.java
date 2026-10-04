package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.OpcUaException;
import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;
import com.github.guoyyit.opcuaserverjava.opcua.model.IdentityToken;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Validates anonymous and username/password activation credentials.
 *
 * <p>Every failed attempt returns the same {@code BAD_IDENTITY_TOKEN_REJECTED}
 * code with no detail about whether the user exists or only the password was
 * wrong. Password comparison is digest based and constant time.
 */
@Component
public class CredentialStore {

    private record Account(byte[] passwordSha256, Set<String> roles) {
    }

    private final Map<String, Account> accounts = Map.of(
            "operator", new Account(sha256("operator-pass"),
                    Set.of("operator", "reader")),
            "admin", new Account(sha256("admin-pass"),
                    Set.of("admin", "operator", "reader")));

    public Set<String> rolesOf(IdentityToken identity) {
        if (identity == null) {
            throw new OpcUaException(StatusCode.BAD_IDENTITY_TOKEN_REJECTED);
        }
        if (identity.isAnonymous()) {
            return Set.of("anonymous", "reader");
        }
        String userName = identity.userName();
        String password = identity.password();
        if (userName == null || password == null) {
            throw new OpcUaException(StatusCode.BAD_IDENTITY_TOKEN_REJECTED);
        }
        Account account = accounts.get(userName);
        byte[] provided = sha256(password);
        // Always compare against a digest (dummy digest for unknown users) so
        // that a failure response does not reveal whether the user exists.
        byte[] expected = account == null ? sha256("not-a-real-password") : account.passwordSha256();
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new OpcUaException(StatusCode.BAD_IDENTITY_TOKEN_REJECTED);
        }
        if (account == null) {
            throw new OpcUaException(StatusCode.BAD_IDENTITY_TOKEN_REJECTED);
        }
        return account.roles();
    }

    private static byte[] sha256(String value) {
        MessageDigest digest = sha256Digest();
        return digest.digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
