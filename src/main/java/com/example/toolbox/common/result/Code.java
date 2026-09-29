package com.example.toolbox.common.result;

import java.util.Arrays;

/**
 * 错误码枚举（契约 §十二）。
 * 实际 14 个：1000~1008 + 2001~2005（v1.8 新增 1008 后由 13 → 14；沿用 P5 已勘误结论）。
 * 每个错误码绑定最终 HTTP 状态：1001→401、1002→403、1000→500，其余→200。
 */
public enum Code {

    SUCCESS(0, "ok", 200),
    SYS_ERROR(1000, "系统异常", 500),
    UNAUTHENTICATED(1001, "登录状态已失效，请重新登录", 401),
    FORBIDDEN(1002, "无操作权限", 403),
    PARAM_INVALID(1003, "参数校验失败", 200),
    NOT_FOUND(1004, "资源不存在", 200),
    CONFLICT(1005, "业务规则冲突", 200),
    BAD_CREDENTIALS(1006, "账号或密码错误", 200),
    ALREADY_INITIALIZED(1007, "系统已初始化", 200),
    /** 登录时该账号已有活跃会话且未请求强制登录（M-18 / 契约 §十二）；仅登录接口使用 */
    FORCE_LOGIN_REQUIRED(1008, "该账号已在其他设备登录，是否强制登录？", 200),
    FILE_TOO_LARGE(2001, "文件超过大小上限", 200),
    CHUNK_INVALID(2002, "分片不完整或分片异常", 200),
    STORAGE_UNAVAILABLE(2003, "存储路径不可用", 200),
    TOOL_DELETED(2004, "工具不存在或已删除", 200),
    FILE_KEY_INVALID(2005, "文件标识无效", 200);

    private final int code;
    private final String message;
    private final int httpStatus;

    Code(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static Code fromCode(int code) {
        return Arrays.stream(values())
                .filter(c -> c.code == code)
                .findFirst()
                .orElse(null);
    }
}
