package com.example.toolbox.upload;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.upload.dto.UploadMergeDTO;
import com.example.toolbox.upload.service.ChunkStorage;
import com.example.toolbox.upload.service.UploadService;
import com.example.toolbox.upload.vo.UploadMergeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 合并落盘时向存储层传**真实长度**（M-22 / B13 验收 A4）。
 *
 * <p>为什么要专门守这条：`UploadService` 原先调用 `storageService.save(fileKey, pipeIn, -1)`
 * （`-1` = 未知长度）。本地实现（`Files.copy`）无所谓，但**对象存储要求已知长度**才能流式上传 ——
 * 因此上传链路必须改成传真值，且**不能再退回 `-1`**（这条最容易在后续重构里被改回去）。
 *
 * <p>做法：`@SpyBean` 包住真实的 {@code StorageService}（`RoutedStorageService`）——
 * 保留真实落盘行为，只额外校验"传进来的 size 参数"。
 */
class UploadKnownSizeTest extends IntegrationTestBase {

    private static final String IDENT = "tbf-a4-size";

    @Autowired
    private UploadService uploadService;
    @Autowired
    private ChunkStorage chunkStorage;

    /** 真实行为不变，仅用于断言"喂给存储层的长度" */
    @SpyBean
    private StorageService storageService;

    @BeforeEach
    void setUpOperator() {
        loginAs(999_999L, "admin", 1L, PermissionCode.TOOL_UPLOAD);
    }

    @Test
    void mergePassesRealSizeInsteadOfUnknownLength() throws Exception {
        // 两个 32 字节分片 → 合计 64
        uploadService.uploadChunk(IDENT, 1, 2, "a.bin", new ByteArrayInputStream(new byte[32]));
        uploadService.uploadChunk(IDENT, 2, 2, "a.bin", new ByteArrayInputStream(new byte[32]));

        assertEquals(64L, chunkStorage.totalSize(IDENT, 2),
                "分片合计应等于各分片大小之和（只 stat 分片文件，不读内容）");

        UploadMergeVO vo = uploadService.merge(mergeDto(2, 64L));

        ArgumentCaptor<Long> size = ArgumentCaptor.forClass(Long.class);
        verify(storageService).save(eq(vo.getFileKey()), any(InputStream.class), size.capture());
        assertEquals(64L, size.getValue().longValue(),
                "★ A4：合并落盘必须传真实长度（= 分片合计），不得再传 -1");
        assertEquals(64L, storageService.size(vo.getFileKey()), "落盘文件长度应与声明一致");
    }

    /** 缺片时「合计长度」与「合并」同口径报 2002（不能算出个偏小的"看起来正常"的长度） */
    @Test
    void totalSizeRejectsMissingChunk() {
        uploadService.uploadChunk(IDENT, 1, 3, "a.bin", new ByteArrayInputStream(new byte[32]));

        BusinessException ex = assertThrows(BusinessException.class, () -> chunkStorage.totalSize(IDENT, 3));
        assertEquals(Code.CHUNK_INVALID, ex.getCode(), "缺片必须报 2002，与合并口径一致");
    }

    private UploadMergeDTO mergeDto(int totalChunks, long fileSize) {
        UploadMergeDTO dto = new UploadMergeDTO();
        dto.setIdentifier(IDENT);
        dto.setFileName("a.bin");
        dto.setFileSize(fileSize);
        dto.setTotalChunks(totalChunks);
        return dto;
    }
}
