package com.example.toolbox.upload;

import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.storage.OssProperties;
import com.example.toolbox.storage.OssStorageService;
import com.example.toolbox.storage.StorageProperties;
import com.example.toolbox.upload.service.OssStsCredentialIssuer;
import com.example.toolbox.upload.service.UploadDirectService;
import com.example.toolbox.upload.vo.UploadModeVO;
import com.example.toolbox.common.log.AuditLogger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 上传模式查询（B14 验收 A1 · A2）。
 *
 * <p>锁死两条口径：
 * <ul>
 *   <li><b>分流依据是「全局开关」</b>（`toolbox.storage.type`），**不是**单文件落点 ——
 *       上传发生时该文件还没有落点记录（方案 §2.2）；</li>
 *   <li>分片参数**唯一来源是后端**：`partSize` 取全局设置 `chunkSize`，上下限为常量，
 *       前端不得写死任一参数。</li>
 * </ul>
 */
class UploadModeTest {

    private static final long MB = 1024L * 1024L;

    private final StorageProperties storageProps = new StorageProperties();
    private final OssProperties ossProps = new OssProperties();
    private final OssStorageService oss = mock(OssStorageService.class);

    private UploadDirectService serviceWith(long chunkSize, long maxFileSize) {
        SettingService settings = new SettingService(null) {
            @Override
            public long getChunkSize() {
                return chunkSize;
            }

            @Override
            public long getMaxFileSize() {
                return maxFileSize;
            }
        };
        return new UploadDirectService(storageProps, ossProps, settings,
                mock(OssStsCredentialIssuer.class), oss, new AuditLogger());
    }

    /** A1：`type=local` → `server`（经服务器中转，原链路） */
    @Test
    void localTypeReportsServerMode() {
        storageProps.setType("local");

        UploadModeVO mode = serviceWith(20 * MB, 10L * 1024 * MB).mode();

        assertEquals("server", mode.getMode(), "local → 经服务器中转");
    }

    /** A1：`type=oss` → `direct`（浏览器直传） */
    @Test
    void ossTypeReportsDirectMode() {
        storageProps.setType("oss");

        UploadModeVO mode = serviceWith(20 * MB, 10L * 1024 * MB).mode();

        assertEquals("direct", mode.getMode(), "oss → 直传");
    }

    /** A2：`partSize` 取全局设置值；上下限与单文件上限一并回显 */
    @Test
    void partSizeComesFromGlobalSetting() {
        storageProps.setType("local");

        UploadModeVO mode = serviceWith(50 * MB, 10L * 1024 * MB).mode();

        assertEquals(50 * MB, mode.getPartSize(), "partSize 必须来自全局设置 chunkSize");
        assertEquals(SettingService.MIN_CHUNK_SIZE, mode.getPartSizeMin());
        assertEquals(SettingService.MAX_CHUNK_SIZE, mode.getPartSizeMax());
        assertEquals(5242880L, mode.getPartSizeMin(), "下限固定 5 MB");
        assertEquals(104857600L, mode.getPartSizeMax(), "上限固定 100 MB");
        assertEquals(10L * 1024 * MB, mode.getMaxFileSize());
    }

    /** 未知 / 空开关按 local 处理（与 StorageBackend.of 同款容错） */
    @Test
    void unknownTypeFallsBackToServer() {
        storageProps.setType("");

        assertEquals("server", serviceWith(20 * MB, 10L * 1024 * MB).mode().getMode());
    }
}
