package com.github.guoyyit.opcuaserverjava.opcua.core;

import com.github.guoyyit.opcuaserverjava.opcua.status.StatusCode;

/**
 * 数据值：携带取值、数据状态码、来源时间戳与服务端采样时间戳。
 * 不可变对象，因此读到的总是某次完整写入后的整体值。
 */
public final class DataValue {

    private final Object value;
    private final StatusCode statusCode;
    private final long sourceTimestamp;
    private final long serverTimestamp;

    public DataValue(Object value, StatusCode statusCode, long sourceTimestamp, long serverTimestamp) {
        this.value = value;
        this.statusCode = statusCode;
        this.sourceTimestamp = sourceTimestamp;
        this.serverTimestamp = serverTimestamp;
    }

    public Object getValue() {
        return value;
    }

    public StatusCode getStatusCode() {
        return statusCode;
    }

    public long getSourceTimestamp() {
        return sourceTimestamp;
    }

    public long getServerTimestamp() {
        return serverTimestamp;
    }
}
