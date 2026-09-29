package com.example.toolbox.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置（toolbox.jwt.*）。
 * 生产环境必须通过环境变量 JWT_SECRET 覆盖默认密钥。
 */
@Data
@Component
@ConfigurationProperties(prefix = "toolbox.jwt")
public class JwtProperties {

    private String secret = "change-me-dev-secret-0123456789abcdefghijkl";
    private long expirationMinutes = 720;
}
