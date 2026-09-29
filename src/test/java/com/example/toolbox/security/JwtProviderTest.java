package com.example.toolbox.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 签发与解析：往返一致；过期令牌 / 篡改令牌必须抛 JwtException。
 */
class JwtProviderTest {

    private final JwtProvider provider = new JwtProvider(new JwtProperties());

    @Test
    void signAndParseRoundTrip() {
        LoginUser user = LoginUser.builder()
                .userId(1L)
                .username("admin")
                .deptId(2L)
                .permissions(Set.of("tool:upload", "user:manage"))
                .build();

        String token = provider.sign(user);
        LoginUser parsed = provider.parse(token);

        assertEquals(1L, parsed.getUserId());
        assertEquals("admin", parsed.getUsername());
        assertEquals(2L, parsed.getDeptId());
        assertEquals(Set.of("tool:upload", "user:manage"), parsed.getPermissions());
    }

    @Test
    void expiredTokenThrows() {
        JwtProperties props = new JwtProperties();
        props.setExpirationMinutes(-1); // 立即使令牌过期
        JwtProvider expiredProvider = new JwtProvider(props);
        String token = expiredProvider.sign(LoginUser.builder().userId(1L).username("a").build());
        assertThrows(JwtException.class, () -> expiredProvider.parse(token));
    }

    @Test
    void tamperedTokenThrows() {
        String token = provider.sign(LoginUser.builder().userId(1L).username("a").build());
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "b" : "a");
        assertThrows(JwtException.class, () -> provider.parse(tampered));
    }
}
