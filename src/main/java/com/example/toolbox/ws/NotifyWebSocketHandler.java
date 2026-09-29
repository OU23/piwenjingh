package com.example.toolbox.ws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 实时通道处理器（契约 §3.3）：连接管理 + 心跳 + 关闭码。
 *
 * <p>握手鉴权已由 {@link AuthHandshakeInterceptor} 完成；本类只处理已建立的连接。
 * 上线后向 {@link WsSessionStore} 注册；断开时注销。服务端每
 * {@code toolbox.ws.heartbeat-interval} 发一次 {@code PING}，
 * 超过 {@code heartbeat-timeout} 无 {@code PONG} 则关闭连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyWebSocketHandler extends TextWebSocketHandler {

    private final WsSessionStore sessionStore;
    private final WsProperties properties;

    /** sessionId → 最近一次 PONG 时刻 */
    private final Map<String, Long> lastPong = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    @PostConstruct
    void startHeartbeat() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(1_000L, properties.getHeartbeatInterval().toMillis());
        scheduler.scheduleWithFixedDelay(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userIdOf(session);
        if (userId == null) {
            // 正常流程不会走到这里（握手已校验），兜底拒绝
            sessionStore.closeSession(session, WsCloseCode.TOKEN_INVALID, "unauthenticated");
            return;
        }
        lastPong.put(session.getId(), System.currentTimeMillis());
        sessionStore.register(userId, session);
        log.info("WebSocket 已建立连接，userId={}", userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayload() != null && message.getPayload().contains(WsMessages.TYPE_PONG)) {
            lastPong.put(session.getId(), System.currentTimeMillis());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        lastPong.remove(session.getId());
        Long userId = userIdOf(session);
        if (userId != null) {
            sessionStore.unregister(userId, session);
        }
        log.info("WebSocket 已关闭，sessionId={}，code={}", session.getId(), status == null ? null : status.getCode());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输异常，sessionId={}：{}", session.getId(), exception.getMessage());
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        long timeout = properties.getHeartbeatTimeout().toMillis();
        for (WebSocketSession s : sessionStore.sessions()) {
            Long last = lastPong.get(s.getId());
            if (last == null) {
                continue;
            }
            if (now - last > timeout) {
                sessionStore.closeSession(s, WsCloseCode.TOKEN_INVALID, "heartbeat timeout");
            } else {
                sessionStore.sendToSession(s, WsMessages.ping());
            }
        }
    }

    private Long userIdOf(WebSocketSession session) {
        Object v = session.getAttributes().get(AuthHandshakeInterceptor.ATTR_USER_ID);
        return v instanceof Long id ? id : null;
    }
}
