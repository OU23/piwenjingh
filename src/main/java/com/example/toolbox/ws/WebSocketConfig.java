package com.example.toolbox.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配（契约 §3.3）。
 * 端点 {@code /ws}；握手走 {@link AuthHandshakeInterceptor} 专用鉴权（不使用 HTTP 鉴权过滤器）。
 * 跨域放开：握手凭证是令牌本身（?token=），且已强制校验，不构成匿名接入。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final NotifyWebSocketHandler notifyWebSocketHandler;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;
    private final WsProperties properties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifyWebSocketHandler, properties.getPath())
                .addInterceptors(authHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
