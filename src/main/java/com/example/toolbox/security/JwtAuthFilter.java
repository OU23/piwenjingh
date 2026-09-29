package com.example.toolbox.security;

import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.R;
import com.example.toolbox.common.util.JsonUtils;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 认证过滤器（真 401）。
 * 公开路径白名单直接放行；其余路径要求 Bearer 令牌，缺失/非法/过期 → HTTP 401 + code 1001。
 * 校验通过后将登录用户写入 UserContext，请求结束后清除。
 *
 * <p><b>单账号单登录（M-18 / 契约 §3.2 规则 2）</b>：令牌解析通过后**追加一步**会话校验 ——
 * 令牌 {@code sid} 必须是该账号的当前会话，否则 401 / 1001（**不是 1008**）。
 * 会话失效统一收敛在此处（鉴权链路单一位置），不在各 Controller 里散落判定。
 *
 * <p><b>服务重启行为（契约 §3.2 v1.18）</b>：会话注册表随进程清空，**原令牌一律 401 / 1001**，
 * 须重新登录（已取消原 A1「首个请求自动接管会话」策略，详见 v1.18 变更说明）。
 *
 * <p>注意：{@code ?token=} 下载通道由 {@link PublicPathProperties} 放行、不经过本过滤器，
 * 其会话校验在 {@code DownloadController} → {@code DownloadTokenStore.consume} 内完成。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final PublicPathProperties publicPathProperties;
    private final SessionRegistry sessionRegistry;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (publicPathProperties.isPublic(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = resolveToken(request);
        if (token == null) {
            writeError(response, Code.UNAUTHENTICATED);
            return;
        }
        try {
            LoginUser user = jwtProvider.parse(token);
            if (!sessionValid(user)) {
                writeError(response, Code.UNAUTHENTICATED);
                return;
            }
            UserContext.set(user);
            try {
                filterChain.doFilter(request, response);
            } finally {
                UserContext.clear();
            }
        } catch (JwtException | IllegalArgumentException e) {
            writeError(response, Code.UNAUTHENTICATED);
        }
    }

    /**
     * 会话校验：令牌 {@code sid} 是否为该账号的当前会话。
     *
     * <p><b>服务重启行为（契约 §3.2 v1.18）</b>：注册表为进程内存实现，重启后清空 ——
     * 此时任何令牌都**不是**当前会话 → 401 / 1001，客户端清理本地登录态并重新登录。
     * **不采用**「重启后由首个请求自动接管会话」的宽松策略：该策略会使服务端活跃会话与
     * 用户可见的登录状态脱节，且在并发请求下存在竞态（见 v1.18 变更说明）。
     */
    private boolean sessionValid(LoginUser user) {
        Long userId = user.getUserId();
        String sid = user.getSid();
        if (userId == null || sid == null) {
            return false;
        }
        return sessionRegistry.isCurrent(userId, sid);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        if (header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (header.startsWith("Bearer")) {
            return header.substring(6);
        }
        return header;
    }

    private void writeError(HttpServletResponse response, Code code) throws IOException {
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtils.toJson(R.fail(code)));
    }
}
