package com.example.toolbox.storage;

import com.example.toolbox.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5（借鉴②）原子落盘专项测试：验证 {@link LocalStorageService#save} 的
 * 「先写同目录临时文件 → 原子改名就位」语义，以及失败时清理临时文件、不污染正式路径。
 *
 * <p>与 {@code LocalStorageServiceTest} 相互独立（不改动其断言），本文件只针对 B5 新增行为。
 *
 * <p>⚠️ 熔断说明（对应验收 A1/A2/A3）：
 * 临时回退 {@code save} 的「原子改名 / 失败清理」实现后，下列用例应**确实变红**，再恢复重跑。
 * 具体可抓的回归：
 * <ul>
 *   <li>回退"失败清理"（去掉 {@code deleteQuietly}）→ {@link #failureDoesNotLeaveTempOrHalfFile} 变红（临时文件残留）；</li>
 *   <li>回退"原子改名"（改为直接 {@code Files.copy(data, target)}）→ 同上用例变红（失败路径会把目标写成半截）；</li>
 *   <li>回退"临时后缀绕过 {@code KEY_PATTERN} 校验"→ 见 {@link #nearMaxLengthKeySavesWithoutPatternReject}（长键 + 后缀不再被误判非法）。</li>
 * </ul>
 *
 * <p>注：A3「文件系统不支持 ATOMIC_MOVE 时回退 REPLACE_EXISTING」为防御分支，单测无法稳定构造，
 * 列为代码审查项（见 {@code docs/acceptance/B5-后端下载与合并稳健性.md} A3）。
 */
class LocalStorageServiceAtomicSaveTest {

    @TempDir
    Path tempDir;

    private LocalStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageService(fixedRoot(tempDir.toString()));
    }

    /** A1：成功保存后，正式路径内容完整，且同目录**无**残留临时文件 */
    @Test
    void successLeavesNoTempFile() throws IOException {
        String key = "tbf-atomic-0001";
        byte[] data = "atomic-save-content".getBytes(StandardCharsets.UTF_8);

        storage.save(key, new ByteArrayInputStream(data), data.length);

        assertTrue(storage.exists(key));
        assertEquals((long) data.length, storage.size(key));
        try (var in = storage.open(key)) {
            assertArrayEquals(data, in.readAllBytes());
        }
        assertNoTempSibling(key);
    }

    /** A1：覆盖已存在文件时，原子替换；替换后仍无临时文件残留 */
    @Test
    void saveReplacesExistingAtomically() throws IOException {
        String key = "tbf-atomic-0002";
        byte[] oldData = "OLD-OLD-OLD".getBytes(StandardCharsets.UTF_8);
        byte[] newData = "NEW-NEW-NEW-NEW".getBytes(StandardCharsets.UTF_8);

        storage.save(key, new ByteArrayInputStream(oldData), oldData.length);
        storage.save(key, new ByteArrayInputStream(newData), newData.length);

        try (var in = storage.open(key)) {
            assertArrayEquals(newData, in.readAllBytes());
        }
        assertNoTempSibling(key);
    }

    /**
     * A2（熔断核心）：写入中途流异常时 ——
     * ① 抛异常；② 正式路径**不被污染**（预置旧内容保持不变，或本就不存在）；③ 临时文件**被清理**。
     *
     * <p>这正是对比"直接写正式路径"的回归：若实现退化成 {@code Files.copy(data, target)}，
     * 失败时会把目标写成半截；此处断言目标**不变**，可抓到该退化。
     */
    @Test
    void failureDoesNotLeaveTempOrHalfFile() throws IOException {
        String key = "tbf-atomic-0003";
        byte[] oldData = "PRISTINE-OLD".getBytes(StandardCharsets.UTF_8);
        // 预置旧内容，证明失败时正式路径不被改写
        storage.save(key, new ByteArrayInputStream(oldData), oldData.length);

        byte[] newData = "THIS-WRITE-WILL-FAIL-MIDWAY-STREAM".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () ->
                storage.save(key, new PartialThenThrowInputStream(newData, 8), newData.length));

        // 正式路径仍是旧内容（未被半截新内容污染）
        try (var in = storage.open(key)) {
            assertArrayEquals(oldData, in.readAllBytes());
        }
        // 临时文件已清理，无孤儿
        assertNoTempSibling(key);
    }

    /**
     * C2：fileKey 接近 64 字符上限时，临时名（键 + 后缀）**不触发** {@code KEY_PATTERN} 64 上限误判。
     *
     * <p>若临时名也走 {@code KEY_PATTERN} 全匹配校验，键用满 64 字符 + 后缀会直接 {@code IllegalArgumentException}。
     * 回归：去掉"临时后缀绕过校验"后，此用例变红。
     */
    @Test
    void nearMaxLengthKeySavesWithoutPatternReject() throws IOException {
        // 64 字符的合法键（全 [A-Za-z0-9._-]）
        String key = "a".repeat(64);
        byte[] data = "near-limit-key".getBytes(StandardCharsets.UTF_8);

        storage.save(key, new ByteArrayInputStream(data), data.length);

        assertTrue(storage.exists(key));
        assertNoTempSibling(key);
    }

    // ---------------- 内部 ----------------

    /** 断言 key 所在目录不存在以 {@link LocalStorageService} 临时后缀结尾的残留文件 */
    private void assertNoTempSibling(String storageKey) throws IOException {
        Path dir = tempDir; // 单键场景：临时文件与目标同目录（即根目录）
        try (Stream<Path> files = Files.list(dir)) {
            boolean hasTemp = files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"));
            assertFalse(hasTemp, "保存后不应残留 .tmp 临时文件");
        }
    }

    /** 夹具：把固定路径包成 {@link StorageRootProvider} */
    private StorageRootProvider fixedRoot(String root) {
        return () -> root;
    }

    /**
     * 读到第 {@code throwAfter} 字节后抛出 {@link IOException} 的输入流，
     * 用于模拟"写入中途流异常 / 磁盘写满"的失败路径。
     */
    private static class PartialThenThrowInputStream extends InputStream {
        private final byte[] data;
        private final int throwAfter;
        private int pos;

        PartialThenThrowInputStream(byte[] data, int throwAfter) {
            this.data = data;
            this.throwAfter = throwAfter;
        }

        @Override
        public int read() throws IOException {
            if (pos >= throwAfter) {
                throw new IOException("simulated stream failure at " + pos);
            }
            return pos < data.length ? (data[pos++] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= throwAfter) {
                throw new IOException("simulated stream failure at " + pos);
            }
            int n = 0;
            while (n < len && pos < data.length && pos < throwAfter) {
                b[off + n++] = (byte) data[pos++];
            }
            return n == 0 ? -1 : n;
        }
    }
}
