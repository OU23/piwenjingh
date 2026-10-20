package com.example.toolbox.config;

import com.example.toolbox.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 安全相关 Bean 装配。
 *
 * <p><b>过滤器顺序（不可调换）</b>：
 * <ol>
 *   <li>{@link CorsFilter} —— 最先执行。它负责给**所有**响应补 {@code Access-Control-Allow-*} 头，
 *       并直接应答 {@code OPTIONS} 预检（不进入后续链路）；</li>
 *   <li>{@link JwtAuthFilter} —— 其次执行，完成令牌校验（真 401）。</li>
 * </ol>
 * 若把 JWT 过滤器排在 CORS 之前，浏览器预检请求（不带 {@code Authorization} 头）
 * 会被判成 401 且响应缺少 CORS 头，前端表现为"所有接口跨域失败"。
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    /** CORS 与 JWT 的固定相对顺序：CORS 必须在前 */
    private static final int CORS_ORDER = Ordered.HIGHEST_PRECEDENCE;
    private static final int JWT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** 前端可能用到的请求头（含分片下载的 Range） */
    private static final List<String> ALLOWED_HEADERS = List.of(
            "Authorization", "Content-Type", "Accept", "Accept-Language",
            "Range", "If-Range", "X-Requested-With", "X-Chunk-Index");

    /** 前端需要读取的响应头（文件名 / 分片信息 / 长度都靠它们） */
    private static final List<String> EXPOSED_HEADERS = List.of(
            "Content-Disposition", "Content-Range", "Accept-Ranges", "Content-Length", "Content-Type");

    private static final List<String> ALLOWED_METHODS = List.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private final JwtAuthFilter jwtAuthFilter;
    private final CorsProperties corsProperties;

    /**
     * 跨域过滤器（M-25 前端独立部署）：来源白名单可由 {@code toolbox.cors.allowed-origin-patterns}
     * 或环境变量 {@code CORS_ORIGIN_PATTERNS} 覆盖。
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(corsProperties.getAllowedOriginPatterns());
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(corsProperties.isAllowCredentials());
        config.setMaxAge(corsProperties.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(CORS_ORDER);
        return registration;
    }

    /** 认证过滤器：排在 CORS 之后，确保预检请求先被 CORS 过滤器应答 */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration() {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(jwtAuthFilter);
        registration.setOrder(JWT_ORDER);
        return registration;
    }
}
