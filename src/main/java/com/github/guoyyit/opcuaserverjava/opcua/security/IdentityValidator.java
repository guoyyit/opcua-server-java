package com.github.guoyyit.opcuaserverjava.opcua.security;

import com.github.guoyyit.opcuaserverjava.opcua.core.UserIdentityToken;

/**
 * 身份令牌校验器。校验失败统一抛出 BAD_IDENTITY_TOKEN_REJECTED，
 * 不区分“用户不存在”还是“口令错误”，避免泄露校验细节。
 */
public interface IdentityValidator {

    UserIdentity validate(UserIdentityToken token);
}
