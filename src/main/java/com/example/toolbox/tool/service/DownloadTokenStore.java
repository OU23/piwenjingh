package com.example.toolbox.tool.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.SessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短时效下载令牌（契约 §9.7 / N-13）。
 *
 * <p>用途：非安全上下文（{@code http://<IP>}）下前端用**原生 `<a>`** 触发下载，
 * 导航请求无法携带 {@code Authorization} 头，故用 {@code ?token=} 作为鉴权载体。
 *
 * <p>实现：随机串 + 服务端内存缓存（契约 §9.7 允许"随机串 + 服务端缓存"）。
 * 令牌**绑定「用户 + 工具 + 版本 + 会话」**，**短时效（≤60s）**，**一次性**（用后即失效）。
 *
 * <p><b>会话绑定（M-18 / 方案 §6 第 7 条，高危漏点）</b>：令牌额外绑定签发时的会话 {@code sid}，
 * 消费时校验该会话是否仍为当前会话 —— 否则被踢用户在令牌有效期内仍可继续下载。
 *
 * <p>安全（security.md + 契约 §9.7）：
 * <ul>
 *   <li>令牌**不得写入访问日志**（本类与调用方均不打印 token 值）；</li>
 *   <li>过期自然失效；</li>
 *   <li>校验时**必须**比对绑定的要素，防令牌被挪用到其他工具 / 版本 / 会话。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DownloadTokenStore {

    /** 令牌有效期（秒），契约 §9.7 建议 ≤ 60 */
    public static final long TTL_SECONDS = 60;

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    private final SessionRegistry sessionRegistry;

    /** token → 绑定信息 */
    private final Map<String, Binding> store = new ConcurrentHashMap<>();

    /** 一次性令牌的绑定内容（M-18 起含会话标识 {@code sid}） */
    public record Binding(Long userId, Long toolId, Long versionId, String sid, long expiresAtMillis) {

        boolean expired(long nowMillis) {
            return nowMillis > expiresAtMillis;
        }

        /**
         * 绑定关系校验：**工具 + 版本**。
         * 不含 userId —— {@code ?token=} 通道的请求者身份**就是**令牌绑定的用户
         * （导航请求带不了 Authorization 头），不存在可比对的外部 userId。
         */
        boolean matches(Long toolId, Long versionId) {
            return this.toolId.equals(toolId)
                    && this.versionId.equals(versionId);
        }
    }

    /** 签发令牌（绑定用户 + 工具 + 版本 + 当前会话） */
    public String issue(Long userId, Long toolId, Long versionId, String sid) {
        byte[] buf = new byte[TOKEN_BYTES];
        random.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        store.put(token, new Binding(userId, toolId, versionId, sid,
                System.currentTimeMillis() + TTL_SECONDS * 1000));
        evictExpired();
        return token;
    }

    /**
     * 校验并**消费**令牌（一次性）。
     *
     * <p>校验不通过一律抛 401（1001）—— 不区分"不存在 / 过期 / 绑定不符 / 会话失效"，
     * 避免通过错误信息探测令牌状态。**不把 token 值写进异常消息或日志。**
     */
    public Binding consume(String token, Long toolId, Long versionId) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        Binding binding = store.remove(token);   // 一次性：取出即失效
        if (binding == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        long now = System.currentTimeMillis();
        if (binding.expired(now) || !binding.matches(toolId, versionId)) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        // M-18：签发该令牌的会话必须仍是当前会话（被踢 / 登出 / 停用 → 立即失效）
        if (!sessionRegistry.isCurrent(binding.userId(), binding.sid())) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        return binding;
    }

    /** 惰性清理过期令牌，避免内存里堆积（无独立定时任务） */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expired(now));
    }
}
