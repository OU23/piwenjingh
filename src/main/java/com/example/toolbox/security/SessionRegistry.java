package com.example.toolbox.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单账号单登录 · 会话注册表（M-18 / 契约 §3.2）。
 *
 * <p><b>部署前提</b>：单实例，进程内存即可（不引 Redis —— 多实例属独立变更，本期不做）。
 *
 * <p><b>服务重启行为（契约 §3.2 v1.18）</b>：注册表随进程清空，**原令牌一律 401 / 1001**、
 * 须重新登录。已**取消**原「重启后首个带令牌的请求自动接管会话」（A1）策略 —— 该策略会使
 * 服务端活跃会话与用户可见的登录状态**脱节**，且在并发请求下存在**竞态**
 * （详见 `契约变更说明-取消重启会话自动接管（v1.18）.md`）。
 *
 * <p>本类只保存会话标识与登录时间，<b>不含任何敏感信息</b>；判定 O(1)。
 */
@Component
public class SessionRegistry {

    /** 会话记录：当前有效会话标识 + 登录时间 */
    public record SessionInfo(String sid, Instant loginTime) {
    }

    private final Map<Long, SessionInfo> registry = new ConcurrentHashMap<>();

    /**
     * 建立 / 替换会话（后写覆盖，天然"踢旧"）。
     *
     * @return 被替换掉的旧 {@code sid}；此前无会话则返回 {@code null}
     */
    public String establish(Long userId, String sid) {
        if (userId == null || sid == null) {
            return null;
        }
        SessionInfo prev = registry.put(userId, new SessionInfo(sid, Instant.now()));
        return prev == null ? null : prev.sid();
    }

    /** 当前会话 {@code sid}；该账号无活跃会话时返回 {@code null} */
    public String currentSid(Long userId) {
        if (userId == null) {
            return null;
        }
        SessionInfo info = registry.get(userId);
        return info == null ? null : info.sid();
    }

    /** 令牌 {@code sid} 是否为该账号的当前会话 */
    public boolean isCurrent(Long userId, String sid) {
        if (userId == null || sid == null) {
            return false;
        }
        SessionInfo info = registry.get(userId);
        return info != null && sid.equals(info.sid());
    }

    /** 该账号是否已有活跃会话（登录接口用它判定是否返回 1008） */
    public boolean hasActive(Long userId) {
        return currentSid(userId) != null;
    }

    /**
     * 移除会话（登出 / 停用）。幂等：无会话时返回 {@code false}，不抛异常。
     *
     * @return 是否确有会话被移除
     */
    public boolean remove(Long userId) {
        return userId != null && registry.remove(userId) != null;
    }

    /**
     * 仅当当前会话正是 {@code sid} 时才移除（防止"旧令牌把新会话误删"）。
     * 用同实例的 {@code remove(key,value)} 保证 CAS 语义：并发被替换时移除失败。
     */
    public boolean removeIfCurrent(Long userId, String sid) {
        if (userId == null || sid == null) {
            return false;
        }
        SessionInfo info = registry.get(userId);
        if (info == null || !sid.equals(info.sid())) {
            return false;
        }
        return registry.remove(userId, info);
    }

    /** 清空（仅供测试夹具使用，生产不调用） */
    public void clear() {
        registry.clear();
    }
}
