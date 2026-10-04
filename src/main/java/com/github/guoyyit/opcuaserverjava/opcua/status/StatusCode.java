package com.github.guoyyit.opcuaserverjava.opcua.status;

/**
 * OPC UA 风格的服务状态码。数值与命名对齐 OPC UA 规范常见状态码，
 * 自定义扩展码使用 0x8Fxxxxxx 段，保证每类失败可区分。
 */
public enum StatusCode {

    GOOD(0x00000000L, "成功"),

    BAD_NODE_ID_UNKNOWN(0x80340000L, "节点不存在"),
    BAD_REFERENCE_ID_UNKNOWN(0x802F0000L, "引用类型不存在"),
    BAD_TYPE_MISMATCH(0x80740000L, "写入值类型与节点数据类型不匹配"),
    BAD_NOT_READABLE(0x80A30000L, "节点不可读"),
    BAD_NOT_WRITABLE(0x80B70000L, "节点不可写"),
    BAD_USER_ACCESS_DENIED(0x801F0000L, "当前用户权限不足"),

    BAD_SECURE_CHANNEL_ID_INVALID(0x8F010001L, "安全通道标识无效"),
    BAD_SECURE_CHANNEL_CLOSED(0x8F010002L, "安全通道已关闭"),
    BAD_SECURE_CHANNEL_TIMEOUT(0x8F010003L, "安全通道已超时"),
    BAD_SEQUENCE_NUMBER_INVALID(0x80240000L, "请求序列号非法"),

    BAD_SESSION_ID_INVALID(0x80250000L, "会话标识无效"),
    BAD_SESSION_CLOSED(0x80190000L, "会话已关闭"),
    BAD_SESSION_NOT_ACTIVATED(0x8F020001L, "会话尚未激活"),
    BAD_SESSION_TIMEOUT(0x8F020002L, "会话空闲超时已失效"),
    BAD_SESSION_LIMIT_REACHED(0x8F020003L, "会话数量达到服务端上限"),
    BAD_IDENTITY_TOKEN_INVALID(0x80200000L, "身份令牌格式无效"),
    BAD_IDENTITY_TOKEN_REJECTED(0x80210000L, "身份凭据校验失败"),

    BAD_SUBSCRIPTION_ID_INVALID(0x80280000L, "订阅标识无效"),
    BAD_SUBSCRIPTION_ID_UNKNOWN(0x8F030001L, "订阅不存在或已被回收"),
    BAD_MONITORED_ITEM_ID_INVALID(0x80090000L, "监控项标识无效"),
    BAD_MONITORED_ITEM_ID_UNKNOWN(0x800A0000L, "监控项不存在或已被回收"),
    BAD_TOO_MANY_MONITORED_ITEMS(0x80DB0000L, "监控项数量达到上限"),
    BAD_NOTIFICATION_QUEUE_LIMIT_REACHED(0x8F030002L, "通知队列深度达到上限"),
    BAD_MESSAGE_NOT_AVAILABLE(0x805E0000L, "指定序列号的通知消息不可用（超出保留窗口）"),
    BAD_NO_DATA(0x80AF0000L, "当前没有可发布的通知"),
    BAD_SUBSCRIPTION_NOT_TRANSFERRED(0x8F030003L, "订阅尚未迁移到当前会话"),

    BAD_INVALID_STATE(0x80AF0001L, "对象状态不允许该操作"),
    BAD_INTERNAL_ERROR(0x806D0000L, "服务端内部错误");

    private final long code;
    private final String description;

    StatusCode(long code, String description) {
        this.code = code;
        this.description = description;
    }

    public long getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isGood() {
        return this == GOOD;
    }

    public boolean isBad() {
        return this != GOOD;
    }
}
