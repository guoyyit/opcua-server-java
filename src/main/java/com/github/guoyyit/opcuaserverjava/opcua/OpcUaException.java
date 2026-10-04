package com.github.guoyyit.opcuaserverjava.opcua;

/**
 * Service level exception carrying a distinguishable {@link StatusCode}.
 */
public class OpcUaException extends RuntimeException {

    private final StatusCode statusCode;

    public OpcUaException(StatusCode statusCode) {
        super(statusCode.name());
        this.statusCode = statusCode;
    }

    public OpcUaException(StatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public StatusCode statusCode() {
        return statusCode;
    }
}
