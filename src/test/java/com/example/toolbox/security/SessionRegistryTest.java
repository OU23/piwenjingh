package com.example.toolbox.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话注册表（M-18 / 契约 §3.2 v1.18）单元测试：建立 / 替换 / 校验 / 移除。
 * 「服务重启后原令牌一律 401（不再自动接管会话）」的用例见 {@link SessionRegistryRestartTest}。
 * 纯单元测试，不启 Spring 上下文。
 */
class SessionRegistryTest {

    private final SessionRegistry registry = new SessionRegistry();

    @Test
    void establishThenIsCurrent() {
        registry.establish(1L, "s1");
        assertEquals("s1", registry.currentSid(1L));
        assertTrue(registry.isCurrent(1L, "s1"));
        assertTrue(registry.hasActive(1L));
    }

    @Test
    void replaceReturnsPreviousSidAndInvalidatesOld() {
        assertNull(registry.establish(1L, "s1"), "首次建立应返回 null");
        assertEquals("s1", registry.establish(1L, "s2"), "应返回被替换的旧 sid");
        assertFalse(registry.isCurrent(1L, "s1"), "旧会话应立即失效");
        assertTrue(registry.isCurrent(1L, "s2"), "新会话应生效");
    }

    @Test
    void unknownUserIsNeverCurrent() {
        assertFalse(registry.isCurrent(1L, "s1"));
        assertFalse(registry.hasActive(1L));
        assertNull(registry.currentSid(1L));
    }

    @Test
    void nullArgumentsAreSafe() {
        assertFalse(registry.isCurrent(null, "s"));
        assertFalse(registry.isCurrent(1L, null));
        assertFalse(registry.removeIfCurrent(null, null));
        assertNull(registry.currentSid(null));
        assertNull(registry.establish(null, "s"), "null userId 应安全返回");
        registry.establish(1L, null);
        assertFalse(registry.hasActive(1L), "null sid 不应建立会话");
    }

    @Test
    void removeIsIdempotent() {
        registry.establish(1L, "s1");
        assertTrue(registry.remove(1L));
        assertFalse(registry.remove(1L), "重复移除应返回 false 而非抛异常");
        assertFalse(registry.hasActive(1L));
    }

    @Test
    void removeIfCurrentOnlyRemovesMatchingSid() {
        registry.establish(1L, "s1");
        assertFalse(registry.removeIfCurrent(1L, "other"), "sid 不匹配不得移除");
        assertTrue(registry.hasActive(1L), "不匹配时会话应仍在");

        assertTrue(registry.removeIfCurrent(1L, "s1"));
        assertFalse(registry.hasActive(1L));
    }
}
