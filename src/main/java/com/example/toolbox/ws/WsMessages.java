package com.example.toolbox.ws;

import com.example.toolbox.common.util.DateTimeUtils;
import com.example.toolbox.common.util.JsonUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 消息体构造（契约 §3.3 / 方案 §3.4）。
 * 统一格式：{@code { "type": string, "data"?: object, "timestamp": string }}。
 */
public final class WsMessages {

    public static final String TYPE_FORCE_LOGOUT = "FORCE_LOGOUT";
    /** 权限已被管理员变更（M-21 / 契约 §3.3 v1.12） */
    public static final String TYPE_PERMISSION_CHANGED = "PERMISSION_CHANGED";
    /** 定向授权（额外可下载工具）集合已变更（契约 §3.3 v1.17） */
    public static final String TYPE_GRANT_CHANGED = "GRANT_CHANGED";
    public static final String TYPE_PING = "PING";
    public static final String TYPE_PONG = "PONG";

    /** 被踢下线通知（服务端 → 客户端） */
    public static String forceLogout() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", "SESSION_REPLACED");
        data.put("message", "你的账号在另一台设备上登录，您即将下线");
        return envelope(TYPE_FORCE_LOGOUT, data);
    }

    /** 权限变更通知（服务端 → 客户端，M-21 / 契约 §3.3 v1.12） */
    public static String permissionChanged() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", TYPE_PERMISSION_CHANGED);
        data.put("message", "账号发生异常，请重新登录");
        return envelope(TYPE_PERMISSION_CHANGED, data);
    }

    /** 心跳探测 */
    public static String ping() {
        return envelope(TYPE_PING, null);
    }

    /**
     * 定向授权变更通知（服务端 → 客户端，契约 §3.3 v1.17）。
     *
     * <p>⚠️ 与 {@link #permissionChanged()} **语义不同、不得混用**：定向授权存于数据库、
     * **每次查库判定** → 客户端**重新拉取工具列表**即生效，**不关连接、不登出**。
     */
    public static String grantChanged() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", TYPE_GRANT_CHANGED);
        data.put("message", "你的可下载工具已更新");
        return envelope(TYPE_GRANT_CHANGED, data);
    }

    private static String envelope(String type, Map<String, Object> data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        if (data != null) {
            msg.put("data", data);
        }
        msg.put("timestamp", DateTimeUtils.format(LocalDateTime.now()));
        return JsonUtils.toJson(msg);
    }

    private WsMessages() {
    }
}
