package com.example.toolbox.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存储路由与**删除隔离**（M-22 / B13 验收 A2 · C8，及"只删一个后端"的口径）。
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>写</b>按全局开关 {@code toolbox.storage.type}（未配置 = local）；</li>
 *   <li><b>读 / 探测 / 删 / 签名</b>按<b>该文件的实际落点</b>（查不到则按写入后端兜底）；</li>
 *   <li>★ <b>删除隔离</b>：删某文件**只作用于它所在的那一个后端**，**绝不"两个都删"**；</li>
 *   <li>本地实现的契约面：{@code presignedUrl} 为空、{@code backend()=LOCAL}、存储键同形。</li>
 * </ul>
 *
 * <p>纯单测（Mockito 假实现，无 Spring 上下文、不触盘、不打真实 OSS）。
 */
class StorageRoutingTest {

    private LocalStorageService local;
    private OssStorageService oss;
    private StorageProperties properties;
    private StorageBackendLocator locator;
    private RoutedStorageService routed;

    @BeforeEach
    void setUp() {
        local = mock(LocalStorageService.class);
        oss = mock(OssStorageService.class);
        properties = new StorageProperties();
        locator = mock(StorageBackendLocator.class);
        routed = new RoutedStorageService(local, oss, properties, locator);
    }

    // ---------------- 落点解析 ----------------

    /** 未知 / 空 / 大小写混写的落库值一律按 local（兼容存量数据） */
    @Test
    void backendValueParsingIsLenient() {
        assertEquals(StorageBackend.LOCAL, StorageBackend.of(null));
        assertEquals(StorageBackend.LOCAL, StorageBackend.of(""));
        assertEquals(StorageBackend.LOCAL, StorageBackend.of("unknown"));
        assertEquals(StorageBackend.LOCAL, StorageBackend.of("local"));
        assertEquals(StorageBackend.OSS, StorageBackend.of("oss"));
        assertEquals(StorageBackend.OSS, StorageBackend.of(" OSS "), "应容忍空白与大小写");
    }

    /** 写入后端默认 local（未配置即本地，与升级前行为一致） */
    @Test
    void writeBackendDefaultsToLocal() {
        assertEquals(StorageBackend.LOCAL, properties.writeBackend(), "未配置 type 时必须仍是本地（升级零行为变化）");

        properties.setType("oss");
        assertEquals(StorageBackend.OSS, properties.writeBackend());

        properties.setType("乱填的");
        assertEquals(StorageBackend.LOCAL, properties.writeBackend(), "非法值按 local，不得抛错");
    }

    // ---------------- 写：按全局开关 ----------------

    @Test
    void saveGoesToWriteBackendOnly() throws IOException {
        try (InputStream in = new ByteArrayInputStream(new byte[8])) {
            properties.setType("oss");
            when(oss.save(anyString(), any(), anyLong())).thenReturn("k");

            assertEquals("k", routed.save("k", in, 8));
            verify(oss).save("k", in, 8);
            verify(local, never()).save(anyString(), any(), anyLong());
        }

        try (InputStream in = new ByteArrayInputStream(new byte[8])) {
            properties.setType("local");
            when(local.save(anyString(), any(), anyLong())).thenReturn("k2");

            assertEquals("k2", routed.save("k2", in, 8));
            verify(local).save("k2", in, 8);
            verify(oss, times(1)).save(anyString(), any(), anyLong()); // 仍只有上面那一次
        }
    }

    // ---------------- 读 / 探测：按落点 ----------------

    @Test
    void readFollowsPerFileBackend() {
        when(locator.backendOf("ossKey")).thenReturn(Optional.of(StorageBackend.OSS));
        when(oss.exists("ossKey")).thenReturn(true);

        assertTrue(routed.exists("ossKey"));
        verify(oss).exists("ossKey");
        verify(local, never()).exists(anyString());
    }

    @Test
    void readFallsBackToWriteBackendWhenLocatorMisses() {
        // 落点查不到（如文件已上传、版本记录尚未落库）
        when(locator.backendOf("orphan")).thenReturn(Optional.empty());
        properties.setType("local");
        when(local.exists("orphan")).thenReturn(false);
        when(oss.exists("orphan")).thenReturn(true);

        assertFalse(routed.exists("orphan"), "兜底应走写入后端（local），而不是碰 OSS");
        verify(local).exists("orphan");
        verify(oss, never()).exists(anyString());
    }

    /** ★ 删除隔离：落点决定唯一目标，另一后端**一个字节都不动** */
    @Test
    void deleteTargetsOnlyTheOwningBackend() throws IOException {
        when(locator.backendOf("ossKey")).thenReturn(Optional.of(StorageBackend.OSS));
        routed.delete("ossKey");
        verify(oss).delete("ossKey");
        verify(local, never()).delete(anyString());

        when(locator.backendOf("localKey")).thenReturn(Optional.of(StorageBackend.LOCAL));
        routed.delete("localKey");
        verify(local).delete("localKey");
        verify(oss, times(1)).delete(anyString()); // 仍只有最初那一次，未被连带
    }

    @Test
    void sizeAndOpenFollowBackend() throws IOException {
        when(locator.backendOf("ossKey")).thenReturn(Optional.of(StorageBackend.OSS));
        when(oss.size("ossKey")).thenReturn(42L);

        assertEquals(42L, routed.size("ossKey"));
        verify(oss).size("ossKey");
        verify(local, never()).size(anyString());

        routed.open("ossKey");
        verify(oss).open("ossKey");
        verify(local, never()).open(anyString());
    }

    // ---------------- 签名 URL：随落点 ----------------

    @Test
    void presignedUrlFollowsBackend() {
        when(locator.backendOf("ossKey")).thenReturn(Optional.of(StorageBackend.OSS));
        when(oss.presignedUrl(eq("ossKey"), eq("工具包.zip"), any(Duration.class)))
                .thenReturn(Optional.of("https://toolbox-files.oss-cn-hangzhou.aliyuncs.com/tools/k.zip?Signature=x"));

        assertTrue(routed.presignedUrl("ossKey", "工具包.zip", Duration.ofHours(1)).isPresent());
        verify(oss).presignedUrl(eq("ossKey"), eq("工具包.zip"), any(Duration.class));
        verify(local, never()).presignedUrl(anyString(), anyString(), any(Duration.class));

        when(locator.backendOf("localKey")).thenReturn(Optional.of(StorageBackend.LOCAL));
        when(local.presignedUrl(eq("localKey"), anyString(), any(Duration.class))).thenReturn(Optional.empty());
        assertTrue(routed.presignedUrl("localKey", "工具包.zip", Duration.ofHours(1)).isEmpty(),
                "本地文件没有直链 → 由上层回退为后端代理地址");
    }

    @Test
    void reportedBackendIsTheWriteBackend() {
        assertEquals(StorageBackend.LOCAL, routed.backend());
        properties.setType("oss");
        assertEquals(StorageBackend.OSS, routed.backend());
    }

    // ---------------- 本地实现的契约面（A2 / C8） ----------------

    @Test
    void localImplementationHasNoDirectUrlAndUsesSameKeyShape() {
        LocalStorageService real = new LocalStorageService(() -> System.getProperty("java.io.tmpdir"));

        assertEquals(StorageBackend.LOCAL, real.backend());
        assertTrue(real.presignedUrl("anyKey", "a.zip", Duration.ofHours(1)).isEmpty(),
                "本地实现不得提供直链（A2）");
        assertTrue(real.generateKey().matches("[A-Za-z0-9._-]{1,64}"),
                "存储键必须与 OSS 实现同形（UUID，C8）");
    }
}
