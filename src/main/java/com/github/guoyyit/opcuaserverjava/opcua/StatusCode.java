package com.github.guoyyit.opcuaserverjava.opcua;

/**
 * OPC UA style status codes returned by service responses.
 */
public enum StatusCode {
    GOOD(0x0000_0000L),

    // Channel / sequencing
    BAD_SECURE_CHANNEL_CLOSED(0x868C_0000L),
    BAD_SECURE_CHANNEL_ID_TOKEN_INVALID(0x8086_0000L),
    BAD_SECURITY_CHECKS_FAILED(0x8013_0000L),
    BAD_SEQUENCE_NUMBER_INVALID(0x8020_0000L),

    // Session
    BAD_SESSION_ID_INVALID(0x8025_0000L),
    BAD_SESSION_CLOSED(0x8026_0000L),
    BAD_SESSION_NOT_ACTIVATED(0x8027_0000L),
    BAD_TOO_MANY_SESSIONS(0x8068_0000L),
    BAD_IDENTITY_TOKEN_REJECTED(0x8020_0100L),

    // Address space
    BAD_NODE_ID_UNKNOWN(0x8034_0000L),
    BAD_NO_CONTINUATION_POINT(0x803E_0000L),
    BAD_CONTINUATION_POINT_LIMIT(0x803F_0000L),
    BAD_ATTRIBUTE_ID_INVALID(0x8035_0000L),

    // Values / permissions
    BAD_TYPE_MISMATCH(0x8074_0000L),
    BAD_NOT_READABLE(0x8073_0000L),
    BAD_NOT_WRITABLE(0x8075_0000L),

    // Subscriptions / monitored items
    BAD_SUBSCRIPTION_ID_INVALID(0x8028_0000L),
    BAD_MONITORED_ITEM_ID_INVALID(0x8029_0000L),
    BAD_TOO_MANY_MONITORED_ITEMS(0x8078_0000L),
    BAD_MONITORED_ITEM_QUEUE_SIZE_INVALID(0x8079_0000L),
    BAD_NOTIFICATION_QUEUE_LIMIT(0x807A_0000L),
    BAD_MESSAGE_NOT_AVAILABLE(0x807B_0000L),
    BAD_PUBLISH_NOT_ALLOWED(0x8080_0000L),

    // Generic service
    BAD_SERVICE_UNSUPPORTED(0x80B1_0000L),
    BAD_INTERNAL_ERROR(0x805B_0000L);

    private final long code;

    StatusCode(long code) {
        this.code = code;
    }

    public long code() {
        return code;
    }

    public static StatusCode fromCode(long code) {
        for (StatusCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return BAD_INTERNAL_ERROR;
    }
}
