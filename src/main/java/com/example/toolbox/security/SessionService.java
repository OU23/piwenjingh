package com.example.toolbox.security;

import com.example.toolbox.ws.WsCloseCode;
import com.example.toolbox.ws.WsMessages;
import com.example.toolbox.ws.WsSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 会话生命周期（M-18 / 契约 §3.2）：登出、账号停用等场景统一走本类，
 * 保证「移除会话记录 + 关闭实时连接」**成对执行**（R3：单一入口，不在各处散落）。
 *
 * <p>幂等：账号本无会话时不报错（logout 不存在的会话不得 500）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRegistry sessionRegistry;
    private final WsSessionStore wsSessionStore;

    /** 登出：移除该账号会话并关闭其 WebSocket 连接 */
    public void logout(Long userId) {
        terminate(userId, "logout");
    }

    /** 强制下线（账号被停用 / 删除）：立即移除会话并关闭连接，使停用即时生效 */
    public void forceLogout(Long userId) {
        terminate(userId, "session terminated");
    }

    /**
     * 权限变更专用终结（M-21 / 契约 §3.3 v1.12）：
     * **先推送通知 → 再关连接 → 最后移除会话**。
     *
     * <p>与 {@link #terminate} 的顺序**刻意相反**：登出 / 停用是"静默下线"（先移除、后关连接）；
     * 权限变更需要客户端**弹窗告知**，因此必须先让消息送达，再关连接。
     *
     * <p>⚠️ 推送异常必须吞掉，且 {@code remove} **无条件执行** ——
     * **会话移除才是权限真正生效的边界**（推送只负责"告知"）。
     */
    public void forceLogoutByPermissionChange(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            wsSessionStore.sendAndClose(userId, WsMessages.permissionChanged(),
                    WsCloseCode.PERMISSION_CHANGED, "permission changed");
        } catch (Exception e) {
            log.warn("推送权限变更通知失败（已忽略），userId={}：{}", userId, e.getMessage());
        }
        // 无论推送成功与否，都必须移除会话：旧令牌下一次请求即 401
        sessionRegistry.remove(userId);
    }

    private void terminate(Long userId, String reason) {
        if (userId == null) {
            return;
        }
        // 先移会话（旧令牌立即失效），再关连接（客户端不再重连）
        if (sessionRegistry.remove(userId)) {
            wsSessionStore.close(userId, WsCloseCode.TOKEN_INVALID, reason);
        }
    }
}
