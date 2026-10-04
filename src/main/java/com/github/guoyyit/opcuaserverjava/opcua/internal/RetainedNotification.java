package com.github.guoyyit.opcuaserverjava.opcua.internal;

import com.github.guoyyit.opcuaserverjava.opcua.message.ServicePayloads.NotificationMessage;

/**
 * Notification held in the republish retention window.
 */
record RetainedNotification(long sequenceNumber, long createdAtMs, NotificationMessage message) {
}
