package com.example.toolbox.ws;

import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.SessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手鉴权（契约 §3.3 / 方案 §5.3）。
 *
 * <p>浏览器 WebSocket API <b>无法</b>携带 {@code Authorization} 头，故握手令牌走
 * {@code ?token=<JWT>} 查询参数（与 §9.7 下载令牌思路一致）。
 *
 * <p><b>拒绝条件</b>（任一即握手失败，不得匿名接入）：
 * <ol>
 *   <li>缺少 token；</li>
 *   <li>令牌解析失败 / 过期；</li>
 *   <li>令牌无 {@code sid}，或 {@code sid} 已非该账号的当前会话。</li>
 * </ol>
 * 校验通过后把 {@code userId} 写入会话属性，供 {@link NotifyWebSocketHandler} 使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    /** 会话属性键：登录用户 ID */
    public static final String ATTR_USER_ID = "userId";

    private final JwtProvider jwtProvider;
    private final SessionRegistry sessionRegistry;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            log.debug("WebSocket 握手被拒：缺少 token");
            return false;
        }
        LoginUser user;
        try {
            user = jwtProvider.parse(token);
        } catch (Exception e) {
            log.debug("WebSocket 握手被拒：令牌无效或已过期");
            return false;
        }
        Long userId = user.getUserId();
        String sid = user.getSid();
        if (userId == null || sid == null) {
            log.debug("WebSocket 握手被拒：令牌缺少会话标识");
            return false;
        }
        if (!sessionRegistry.isCurrent(userId, sid)) {
            log.debug("WebSocket 握手被拒：会话已失效，userId={}", userId);
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}
