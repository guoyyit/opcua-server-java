package com.github.guoyyit.opcuaserverjava.opcua.core;

import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;

/**
 * 服务层统一异常，携带可区分的 {@link StatusCode}。
 */
public class OpcUaException extends RuntimeException {

    private final StatusCode statusCode;

    public OpcUaException(StatusCode statusCode) {
        super(statusCode.getDescription());
        this.statusCode = statusCode;
    }

    public OpcUaException(StatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public StatusCode getStatusCode() {
        return statusCode;
    }
}
