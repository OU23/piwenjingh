package com.example.toolbox.ws;

import com.example.toolbox.security.JwtProperties;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WebSocket 握手鉴权（M-18 / 契约 §3.3）单元测试。
 * 覆盖验收 <b>A7（握手鉴权）/ C9（未登录拒绝）</b>：不得匿名接入。
 */
class AuthHandshakeInterceptorTest {

    private static final Long UID = 7L;

    private final JwtProvider provider = new JwtProvider(new JwtProperties());
    private final SessionRegistry registry = new SessionRegistry();
    private final AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor(provider, registry);

    private ServerHttpRequest request(String query) {
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        when(req.getURI()).thenReturn(URI.create("http://localhost/ws" + (query == null ? "" : query)));
        return req;
    }

    private Map<String, Object> attrs() {
        return new HashMap<>();
    }

    private String tokenWithSid(String sid) {
        return provider.sign(LoginUser.builder()
                .userId(UID).username("u").deptId(1L).sid(sid).build());
    }

    /** C9：缺少 token / 空 token → 拒绝握手 */
    @Test
    void rejectsHandshakeWithoutToken() {
        assertFalse(interceptor.beforeHandshake(request(null), mock(ServerHttpResponse.class), null, attrs()));
        assertFalse(interceptor.beforeHandshake(request("?token="), mock(ServerHttpResponse.class), null, attrs()));
        assertFalse(interceptor.beforeHandshake(request("?token=%20"), mock(ServerHttpResponse.class), null, attrs()));
    }

    /** A7：非法令牌 → 拒绝握手 */
    @Test
    void rejectsInvalidToken() {
        assertFalse(interceptor.beforeHandshake(
                request("?token=not-a-jwt"), mock(ServerHttpResponse.class), null, attrs()));
    }

    /** 无会话标识（旧令牌）→ 拒绝 */
    @Test
    void rejectsTokenWithoutSid() {
        String token = provider.sign(LoginUser.builder().userId(UID).username("u").build());
        assertFalse(interceptor.beforeHandshake(
                request("?token=" + token), mock(ServerHttpResponse.class), null, attrs()));
    }

    /** 令牌 sid 已非当前会话（被踢 / 登出）→ 拒绝 */
    @Test
    void rejectsStaleSession() {
        registry.establish(UID, "current-session");
        assertFalse(interceptor.beforeHandshake(
                request("?token=" + tokenWithSid("old-session")),
                mock(ServerHttpResponse.class), null, attrs()));
    }

    /** A7：有效令牌 → 握手通过并把 userId 写进会话属性 */
    @Test
    void acceptsValidTokenAndBindsUserId() {
        registry.establish(UID, "s1");
        Map<String, Object> attributes = attrs();

        assertTrue(interceptor.beforeHandshake(
                request("?token=" + tokenWithSid("s1")),
                mock(ServerHttpResponse.class), null, attributes));
        assertEquals(UID, attributes.get(AuthHandshakeInterceptor.ATTR_USER_ID));
    }
}
