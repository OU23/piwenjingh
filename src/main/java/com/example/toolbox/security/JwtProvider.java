package com.example.toolbox.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JWT 签发与解析。密钥取自配置（生产走环境变量）。令牌载荷只放非敏感声明。
 */
@Component
public class JwtProvider {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtProvider(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String sign(LoginUser user) {
        long now = System.currentTimeMillis();
        Date expiration = new Date(now + properties.getExpirationMinutes() * 60_000L);
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim("username", user.getUsername())
                .claim("realName", user.getRealName())
                .claim("deptId", user.getDeptId())
                .claim("deptName", user.getDeptName())
                .claim("perms", new ArrayList<>(user.getPermissions()))
                .claim("sid", user.getSid())
                .issuedAt(new Date(now))
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = claims.getSubject() == null ? null : Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            String realName = claims.get("realName", String.class);
            Long deptId = claims.get("deptId", Long.class);
            String deptName = claims.get("deptName", String.class);
            List<String> perms = claims.get("perms", List.class);
            Set<String> permissions = perms == null ? Collections.emptySet() : new HashSet<>(perms);
            String sid = claims.get("sid", String.class);
            return LoginUser.builder()
                    .userId(userId)
                    .username(username)
                    .realName(realName)
                    .deptId(deptId)
                    .deptName(deptName)
                    .permissions(permissions)
                    .sid(sid)
                    .build();
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtException("令牌解析失败", e);
        }
    }

    /** 令牌有效期（秒），供登录响应 expiresIn 使用 */
    public long getExpiresInSeconds() {
        return properties.getExpirationMinutes() * 60L;
    }
}
