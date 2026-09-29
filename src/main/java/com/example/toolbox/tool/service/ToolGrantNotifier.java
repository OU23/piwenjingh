package com.example.toolbox.tool.service;

import com.example.toolbox.ws.WsMessages;
import com.example.toolbox.ws.WsSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 定向授权变更通知（契约 §3.3 v1.17）。
 *
 * <p><b>为什么单独成类</b>：两个授权保存入口（用户侧 `PUT /api/users/{id}/tool-grants`、
 * 工具侧 `PUT /api/tools/{id}/grants`）都要推送，抽此单点避免散落（R3）。
 *
 * <p><b>与 M-21 权限变更的关键差别</b>：
 * <ul>
 *   <li>权限点存于 **JWT 快照** → 变更后必须重登 ⇒ 推 {@code PERMISSION_CHANGED} 并**关闭连接**（`4004`）；</li>
 *   <li>定向授权存于**数据库**、**每次查库判定** → **刷新即生效** ⇒ 只推 {@code GRANT_CHANGED}，
 *       **连接保持、不下线**（`wsSessionStore.send(...)`，<b>不是</b> {@code sendAndClose(...)}）。</li>
 * </ul>
 *
 * <p>约定：<b>豁免当前操作人</b>（自己改自己不必自我打断）；推送异常**吞掉**，
 * 不得影响保存结果（保存事务已在调用本类前提交）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolGrantNotifier {

    private final WsSessionStore wsSessionStore;

    /**
     * 向「可见工具集合实际发生变化」的用户推送 {@code GRANT_CHANGED}。
     *
     * @param affectedUserIds 受影响用户（由 {@link ToolGrantService} 按**落库结果**算出的 diff）
     * @param operatorId      当前操作人（**不推送**）；为 null 时不做豁免
     */
    public void notifyChanged(Collection<Long> affectedUserIds, Long operatorId) {
        if (affectedUserIds == null || affectedUserIds.isEmpty()) {
            return;
        }

        String payload = WsMessages.grantChanged();
        for (Long userId : affectedUserIds) {
            if (userId == null || userId.equals(operatorId)) {
                continue;
            }
            try {
                wsSessionStore.send(userId, payload);
            } catch (Exception e) {
                // 推送只负责"告知"，失败不影响业务结果
                log.warn("推送授权变更通知失败（已忽略），userId={}：{}", userId, e.getMessage());
            }
        }
    }
}
