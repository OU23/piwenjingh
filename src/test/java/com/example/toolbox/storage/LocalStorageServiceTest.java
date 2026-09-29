package com.example.toolbox.storage;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地存储实现：保存/读取/删除/存在性/大小 往返一致；非法存储键（路径穿越）被拒绝；
 * 未配置根路径时给出明确错误。
 */
class LocalStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageService(fixedRoot(tempDir.toString()));
    }

    @Test
    void saveOpenDeleteRoundTrip() throws IOException {
        String key = "tbf-abcdef1234-1024";
        byte[] data = "hello-toolbox".getBytes(StandardCharsets.UTF_8);

        storage.save(key, new ByteArrayInputStream(data), data.length);

        assertTrue(storage.exists(key));
        assertEquals((long) data.length, storage.size(key));
        try (var in = storage.open(key)) {
            assertArrayEquals(data, in.readAllBytes());
        }
        storage.delete(key);
        assertFalse(storage.exists(key));
    }

    @Test
    void invalidKeyRejected() {
        byte[] empty = new byte[0];
        assertThrows(IllegalArgumentException.class,
                () -> storage.save("../escape", new ByteArrayInputStream(empty), 0));
        assertThrows(IllegalArgumentException.class,
                () -> storage.save("bad/key", new ByteArrayInputStream(empty), 0));
    }

    /**
     * 未配置根路径 → **2003（存储路径不可用）**，而不是 500。
     *
     * <p>注：B4 起把这里的 {@code IllegalStateException} 收敛为业务码 2003 ——
     * 依据契约 §十二（2003 = 存储路径不可用：路径不存在 / 不可写）与 R2
     * （可预期的配置问题不该报成服务端故障 500）。**这是随生产行为变更而更新的断言，
     * 不是为让用例通过而放宽断言。**
     */
    @Test
    void unconfiguredRootGivesStorageUnavailable() {
        LocalStorageService unconfigured = new LocalStorageService(fixedRoot(""));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> unconfigured.exists("x"));
        assertEquals(Code.STORAGE_UNAVAILABLE, ex.getCode());
    }

    /** 夹具：把固定路径包成 {@link StorageRootProvider}（存储层已改为经接口取根路径） */
    private StorageRootProvider fixedRoot(String root) {
        return () -> root;
    }
}
