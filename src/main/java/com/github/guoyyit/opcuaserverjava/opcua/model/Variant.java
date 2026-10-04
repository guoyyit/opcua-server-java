package com.github.guoyyit.opcuaserverjava.opcua.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Value container supporting the scalar types used in loopback validation.
 */
public final class Variant {

    private final Object value;

    @JsonCreator
    public Variant(Object value) {
        if (value != null && !isSupported(value)) {
            throw new IllegalArgumentException("unsupported variant type: " + value.getClass());
        }
        this.value = value;
    }

    @JsonValue
    public Object value() {
        return value;
    }

    public Class<?> valueType() {
        return value == null ? Void.class : value.getClass();
    }

    public static boolean isAssignable(Class<?> expected, Object candidate) {
        if (candidate == null) {
            return true;
        }
        Class<?> actual = candidate.getClass();
        // All integral/floating point scalars are interchangeable for the
        // loopback demo; the written value is coerced to the node's type.
        if (Number.class.isAssignableFrom(expected) && Number.class.isAssignableFrom(actual)) {
            return true;
        }
        if (expected == Integer.class && actual == Integer.class) {
            return true;
        }
        if (expected == Long.class && (actual == Long.class || actual == Integer.class)) {
            return true;
        }
        return expected.isAssignableFrom(actual);
    }

    private static boolean isSupported(Object value) {
        return value instanceof Boolean
                || value instanceof Number
                || value instanceof String
                || value instanceof byte[];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Variant variant)) {
            return false;
        }
        if (value instanceof byte[] a && variant.value instanceof byte[] b) {
            return java.util.Arrays.equals(a, b);
        }
        return Objects.equals(value, variant.value);
    }

    @Override
    public int hashCode() {
        if (value instanceof byte[] bytes) {
            return java.util.Arrays.hashCode(bytes);
        }
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return "Variant[" + value + "]";
    }
}
