package com.github.guoyyit.opcuaserverjava.opcua.model;

import com.github.guoyyit.opcuaserverjava.opcua.StatusCode;

import java.time.Instant;

/**
 * A value carrying a source timestamp and a status code.
 */
public record DataValue(Variant value, StatusCode statusCode, Instant sourceTime) {

    public static DataValue good(Object value) {
        return new DataValue(new Variant(value), StatusCode.GOOD, Instant.now());
    }

    public static DataValue good(Variant value) {
        return new DataValue(value, StatusCode.GOOD, Instant.now());
    }
}
