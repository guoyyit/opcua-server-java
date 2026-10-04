package com.github.guoyyit.opcuaserverjava.opcua.message;

/**
 * Service request discriminators.
 */
public enum RequestType {
    OPEN_SECURE_CHANNEL,
    CLOSE_SECURE_CHANNEL,
    CREATE_SESSION,
    ACTIVATE_SESSION,
    CLOSE_SESSION,
    BROWSE,
    BROWSE_NEXT,
    READ,
    WRITE,
    CREATE_SUBSCRIPTION,
    DELETE_SUBSCRIPTION,
    CREATE_MONITORED_ITEMS,
    MODIFY_MONITORED_ITEM,
    SET_MONITORING_MODE,
    PUBLISH,
    UNPUBLISH,
    REPUBLISH
}
