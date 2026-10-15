package com.example.toolbox.upload;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.storage.OssProperties;
import com.example.toolbox.storage.OssStorageService;
import com.example.toolbox.storage.StorageProperties;
import com.example.toolbox.upload.dto.UploadDirectCompleteDTO;
import com.example.toolbox.upload.service.OssStsCredentialIssuer;
import com.example.toolbox.upload.service.UploadDirectService;
import com.example.toolbox.upload.vo.UploadMergeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 直传完成登记复核（B14 验收 A11 · A12 · A13 · A15）。
 *
 * <p><b>不打真实 OSS</b>：`OssStorageService` 为替身，只验证"我方发出的调用与错误映射"。
 * 「不写业务表」（A14）由集成用例 {@code UploadDirectEndpointTest} 覆盖。
 */
class UploadDirectCompleteTest {

    private static final long MB = 1024L * 1024L;
    private static final long DEPT_ID = 7L;
    private static final String OWN_KEY = "tools/7/0123456789abcdef0123456789abcdef.zip";

    private final StorageProperties storageProps = new StorageProperties();
    private final OssProperties ossProps = new OssProperties();
    private final OssStorageService oss = mock(OssStorageService.class);

    private long maxFileSize = 10L * 1024 * MB;
    private UploadDirectService service;

    @BeforeEach
    void setUp() {
        SettingService settings = new SettingService(null) {
            @Override
            public long getChunkSize() {
                return 20 * MB;
            }

            @Override
            public long getMaxFileSize() {
                return maxFileSize;
            }
        };
        service = new UploadDirectService(storageProps, ossProps, settings,
                mock(OssStsCredentialIssuer.class), oss, new AuditLogger());
        UserContext.set(LoginUser.builder().userId(42L).username("zhangsan").deptId(DEPT_ID).build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UploadDirectCompleteDTO dto(String objectKey, long fileSize) {
        UploadDirectCompleteDTO dto = new UploadDirectCompleteDTO();
        dto.setObjectKey(objectKey);
        dto.setFileName("demo.zip");
        dto.setFileSize(fileSize);
        return dto;
    }

    // ---------------- A11 前缀校验 ----------------

    @Test
    void foreignObjectKeyIsRejectedWithoutTouchingOss() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.complete(dto("tools/999/0123456789abcdef0123456789abcdef.zip", 10 * MB)));

        assertEquals(Code.PARAM_INVALID, ex.getCode());
        verifyNoInteractions(oss);
    }

    // ---------------- A12 对象不存在 ----------------

    @Test
    void missingObjectReportsIncompleteUpload() throws Exception {
        when(oss.exists(OWN_KEY)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(dto(OWN_KEY, 10 * MB)));

        assertEquals(Code.CHUNK_INVALID, ex.getCode());
        assertEquals(200, ex.getCode().getHttpStatus());
        verify(oss, never()).delete(anyString());
    }

    @Test
    void noSuchFileFromHeadIsTreatedAsIncomplete() throws IOException {
        when(oss.exists(OWN_KEY)).thenReturn(true);
        when(oss.size(OWN_KEY)).thenThrow(new NoSuchFileException("gone"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(dto(OWN_KEY, 10 * MB)));

        assertEquals(Code.CHUNK_INVALID, ex.getCode());
    }

    // ---------------- A13 大小不符 ----------------

    @Test
    void sizeMismatchReportsActualAndDeclaredThenDeletesObject() throws IOException {
        when(oss.exists(OWN_KEY)).thenReturn(true);
        when(oss.size(OWN_KEY)).thenReturn(2L * 1024 * MB);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.complete(dto(OWN_KEY, 1024 * MB)));

        assertEquals(Code.CHUNK_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains(String.valueOf(2L * 1024 * MB)), "message 须含实际大小：" + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(1024 * MB)), "message 须含声明大小：" + ex.getMessage());
        verify(oss).delete(OWN_KEY);
    }

    // ---------------- A14/A15 正常路径与错误边界 ----------------

    @Test
    void successReturnsMergeShapeWithFileKeyEqualToObjectKey() throws IOException {
        when(oss.exists(OWN_KEY)).thenReturn(true);
        when(oss.size(OWN_KEY)).thenReturn(10 * MB);

        UploadMergeVO vo = service.complete(dto(OWN_KEY, 10 * MB));

        assertEquals(OWN_KEY, vo.getFileKey(), "fileKey 必须就是 objectKey（后续创建工具/版本直接复用）");
        assertEquals("demo.zip", vo.getFileName());
        assertEquals(10 * MB, vo.getFileSize());
        verify(oss, never()).delete(anyString());
    }

    @Test
    void ossFailureIsIsolatedAs2006Not500() {
        when(oss.exists(OWN_KEY)).thenThrow(new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(dto(OWN_KEY, 10 * MB)));

        assertEquals(Code.STORAGE_OSS_UNAVAILABLE, ex.getCode());
        assertEquals(200, ex.getCode().getHttpStatus(), "OSS 故障必须 HTTP 200 + 2006");
    }

    @Test
    void declaredSizeOverLimitIsRejectedBeforeHead() {
        maxFileSize = 10 * MB;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.complete(dto(OWN_KEY, 11 * MB)));

        assertEquals(Code.FILE_TOO_LARGE, ex.getCode());
        verifyNoInteractions(oss);
    }
}
