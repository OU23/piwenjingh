package com.example.toolbox.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接注册表：{@code userId → WebSocketSession}（并发安全）。
 *
 * <p>只负责"连接的生命周期与帧发送"，不承载业务判定；会话有效性由
 * {@link com.example.toolbox.security.SessionRegistry} 判定。
 * 发送 / 关闭失败一律**吞掉异常**：推送失败绝不能导致登录、登出、停用等主流程失败
 * （方案 §5.2 第 6 步；客户端另有 401 / 4002 双保险）。
 */
@Slf4j
@Component
public class WsSessionStore {

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** 注册连接：若该账号已有旧连接，关闭旧连接（4003）保留新连接（方案 §5.3） */
    public void register(Long userId, WebSocketSession session) {
        if (userId == null || session == null) {
            return;
        }
        WebSocketSession old = sessions.put(userId, session);
        if (old != null && old != session) {
            closeSession(old, WsCloseCode.DUPLICATE_CONNECTION, "another connection established");
        }
    }

    /** 注销连接（仅当当前登记的就是该 session 时才移除，避免误删新连接） */
    public void unregister(Long userId, WebSocketSession session) {
        if (userId != null && session != null) {
            sessions.remove(userId, session);
        }
    }

    /** 该账号是否有在线连接 */
    public boolean isConnected(Long userId) {
        WebSocketSession s = userId == null ? null : sessions.get(userId);
        return s != null && s.isOpen();
    }

    /** 当前登记的全部连接（心跳遍历用） */
    public Collection<WebSocketSession> sessions() {
        return sessions.values();
    }

    /** 向该账号推送文本帧（无连接则忽略） */
    public void send(Long userId, String payload) {
        WebSocketSession s = userId == null ? null : sessions.get(userId);
        if (s == null) {
            return;
        }
        sendToSession(s, payload);
    }

    /** 推送下线通知并关闭连接（被踢：先消息、后关闭，双保险） */
    public void sendAndClose(Long userId, String payload, int code, String reason) {
        WebSocketSession s = userId == null ? null : sessions.get(userId);
        if (s == null) {
            return;
        }
        sendToSession(s, payload);
        closeSession(s, code, reason);
        sessions.remove(userId, s);
    }

    /** 仅关闭该账号连接（登出 / 停用 / 令牌失效） */
    public void close(Long userId, int code, String reason) {
        WebSocketSession s = userId == null ? null : sessions.get(userId);
        if (s == null) {
            return;
        }
        closeSession(s, code, reason);
        sessions.remove(userId, s);
    }

    /** 按连接发送（心跳用） */
    public void sendToSession(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            // 同一 session 并发发送会触发 TEXT_PARTIAL_WRITING，需串行化
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.warn("WebSocket 推送失败（已忽略）：{}", e.getMessage());
        }
    }

    /** 按连接关闭（心跳超时用） */
    public void closeSession(WebSocketSession session, int code, String reason) {
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(code, reason));
            }
        } catch (Exception e) {
            log.warn("WebSocket 关闭失败（已忽略）：{}", e.getMessage());
        }
    }

    /** 清空（仅供测试夹具使用，生产不调用） */
    public void clear() {
        sessions.clear();
    }
}
