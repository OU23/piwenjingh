package com.example.toolbox.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 认证过滤器白名单。
 *
 * <p><b>两类放行</b>：
 * <ol>
 *   <li>{@link #publicPaths} —— 完全公开（无需任何凭证）：登录、初始化状态查询、初始化提交（契约 §三/§十一）；</li>
 *   <li>{@link #tokenAuthPaths} —— **仅当请求确实携带非空 {@code token} 参数时**才放行认证过滤器，
 *       供契约 §9.5 / §9.7 的「{@code ?token=} 短时效令牌下载」通道使用：
 *       原生 {@code <a>} 导航请求无法携带 {@code Authorization} 头，若在此处按 Bearer 拦截，
 *       该通道永远到不了控制器。放行后**令牌有效性、绑定关系、权限与可见范围
 *       全部由控制器内逐项校验**（无效 / 过期 / 越权 → 401），因此**并未放宽鉴权要求**，
 *       也未产生任何无鉴权直链（契约 §9.5 明确禁止）。</li>
 * </ol>
 *
 * <p>白名单只有本类一个所有者，禁止在其他地方另写路径判断。
 */
@Data
@Component
@ConfigurationProperties(prefix = "toolbox.security")
public class PublicPathProperties {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 完全公开路径（无需任何凭证） */
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/api/auth/login",
            "/api/system/init-status",
            "/api/system/init",
            // WebSocket 握手路径（M-18）：握手无法携带 Authorization 头，故不经过本过滤器；
            // 其鉴权由 ws 包的 AuthHandshakeInterceptor 完成（校验 ?token= 与 sid，拒绝匿名/失效令牌）。
            "/ws"));

    /** 允许以 {@code ?token=} 作为鉴权载体的路径（携带非空 token 时才放行过滤器） */
    private List<String> tokenAuthPaths = new ArrayList<>(List.of(
            "/api/tools/*/download"));

    public boolean isPublic(HttpServletRequest request) {
        String path = pathOf(request);
        if (publicPaths != null && publicPaths.contains(path)) {
            return true;
        }
        return tokenCarrierAllowed(path, request);
    }

    /** 路径是否为 token 载体路径，且本次请求确实带了 token 参数 */
    private boolean tokenCarrierAllowed(String path, HttpServletRequest request) {
        if (tokenAuthPaths == null || tokenAuthPaths.isEmpty()) {
            return false;
        }
        String token = request.getParameter("token");
        if (token == null || token.isBlank()) {
            return false;
        }
        for (String pattern : tokenAuthPaths) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length()) : uri;
    }
}
