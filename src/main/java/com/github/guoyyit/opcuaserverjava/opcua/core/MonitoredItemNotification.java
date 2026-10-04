package com.github.guoyyit.opcuaserverjava.opcua.core;

/**
 * 单个监控项的数据变化通知。
 */
public record MonitoredItemNotification(long clientHandle,
                                        long monitoredItemId,
                                        DataValue value) {
}
