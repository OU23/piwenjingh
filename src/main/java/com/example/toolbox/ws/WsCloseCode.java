package com.example.toolbox.ws;

/**
 * WebSocket 关闭码（契约 §3.3 / 方案 §3.4）。
 * 取值落在 4000~4999（应用私有区间），前端据此区分「网络断了」与「被踢了」。
 */
public final class WsCloseCode {

    /** 令牌无效 / 过期 / 会话已失效 —— 前端不重连，静默回登录页 */
    public static final int TOKEN_INVALID = 4001;

    /** 会话被替代（被踢）—— 前端不重连，按被踢处理 */
    public static final int SESSION_REPLACED = 4002;

    /** 同一账号重复连接 —— 关闭较早的那条连接 */
    public static final int DUPLICATE_CONNECTION = 4003;

    /** 权限已变更、会话被终结（M-21 / 契约 §3.3 v1.12）—— 前端**不重连**，按"权限变更"提示后回登录页 */
    public static final int PERMISSION_CHANGED = 4004;

    private WsCloseCode() {
    }
}
