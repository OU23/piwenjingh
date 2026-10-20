package com.example.toolbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域配置（toolbox.cors.*）。
 *
 * <p><b>为什么需要</b>：前端**独立部署**（如 {@code http://192.168.34.21:5173}），与后端不同源。
 * 浏览器对跨源请求先发 {@code OPTIONS} 预检，若响应缺少 {@code Access-Control-Allow-Origin}
 * 或预检被鉴权过滤器拦成 401，前端所有接口都会失败（控制台报 CORS 错误）。
 *
 * <p><b>默认值</b>：{@code *}（开发期最省事）。鉴权走 {@code Authorization} 头而非 Cookie，
 * 因此宽泛的 {@code Origin} 不构成 CSRF 面；**生产环境请用环境变量收紧**：
 * <pre>
 * CORS_ORIGIN_PATTERNS=https://toolbox.example.com,https://admin.example.com
 * </pre>
 *
 * <p>要点：开启了 {@code allowCredentials} 时 {@code allowedOrigins} 不能用 {@code *}，
 * 必须走 {@code allowedOriginPatterns}；本项目不用 Cookie，默认关闭凭据。
 */
@Data
@Component
@ConfigurationProperties(prefix = "toolbox.cors")
public class CorsProperties {

    /** 允许的来源（支持通配符，如 {@code http://192.168.*:*}）；{@code *} 表示任意来源 */
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("*"));

    /** 是否允许携带凭据（Cookie / Authorization 由浏览器自动处理；本项目用显式 Header，保持 false） */
    private boolean allowCredentials = false;

    /** 预检结果缓存秒数（减少 OPTIONS 往返） */
    private long maxAgeSeconds = 3600;
}
