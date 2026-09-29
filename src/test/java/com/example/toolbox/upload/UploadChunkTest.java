package com.example.toolbox.upload;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.storage.StorageProperties;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.upload.dto.UploadCheckDTO;
import com.example.toolbox.upload.dto.UploadMergeDTO;
import com.example.toolbox.upload.service.ChunkStorage;
import com.example.toolbox.upload.service.UploadService;
import com.example.toolbox.upload.vo.UploadCheckVO;
import com.example.toolbox.upload.vo.UploadMergeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片上传全流程（B3 计划 T10）。
 * 覆盖：检查 → 分片 → 合并；断点续传（已传分片返回）；缺失分片 2002；
 * 超上限 2001；合并后临时目录为空；重复取消幂等；identifier 路径安全。
 */
class UploadChunkTest extends IntegrationTestBase {

    @Autowired
    private UploadService uploadService;
    @Autowired
    private ChunkStorage chunkStorage;
    @Autowired
    private StorageService storageService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private StorageProperties storageProperties;

    private static final String IDENT = "tbf-test-1024";

    @BeforeEach
    void setUpOperator() {
        loginAs(999_999L, "admin", 1L, PermissionCode.TOOL_UPLOAD);
    }

    /**
     * 夹具自清理：把本类留下的分片目录删掉。
     *
     * <p>并非所有用例都会走到"合并"或"取消"（例如只验证 check 返回的用例、被 2001/2002 拦下的用例），
     * 那些用例会把分片留在临时目录里 —— 这些残留会混进下次运行乃至真实上传的中间文件里，
     * 属于 testing-rules 明令禁止的"污染"。故统一在 {@code @AfterEach} 清理本类用到的标识。
     */
    @AfterEach
    void cleanChunks() {
        for (String identifier : new String[]{IDENT, "tbf-200", "tbf-nomatch"}) {
            chunkStorage.clean(identifier);
        }
    }

    private byte[] chunkBytes(int seq, int size) {
        byte[] data = new byte[size];
        // 每片内容可区分，便于校验合并顺序
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (seq + i % 7);
        }
        return data;
    }

    /** 断点续传：check 返回已成功接收的分片序号（从 1 开始，升序） */
    @Test
    void checkReturnsUploadedChunksAscending() {
        // 乱序上传 3 片中的第 1、3 片
        uploadService.uploadChunk(IDENT, 3, 3, "a.bin", new ByteArrayInputStream(chunkBytes(3, 16)));
        uploadService.uploadChunk(IDENT, 1, 3, "a.bin", new ByteArrayInputStream(chunkBytes(1, 16)));

        UploadCheckVO vo = uploadService.check(checkDto(3));
        assertEquals(List.of(1, 3), vo.getUploadedChunks(), "应升序返回已传分片序号");
    }

    /** 无已传分片时返回空数组（不是 null） */
    @Test
    void checkOnFreshIdentifierReturnsEmptyList() {
        UploadCheckVO vo = uploadService.check(checkDto(3));
        assertNotNull(vo.getUploadedChunks(), "不得返回 null");
        assertTrue(vo.getUploadedChunks().isEmpty(), "新标识应返回空数组");
    }

    /** 完整流程：3 片 → 合并 → 内容正确 + 临时目录已清空 */
    @Test
    void mergeProducesCorrectFileAndCleansChunks() throws IOException {
        byte[] c1 = chunkBytes(1, 32);
        byte[] c2 = chunkBytes(2, 32);
        byte[] c3 = chunkBytes(3, 16);
        uploadService.uploadChunk(IDENT, 1, 3, "a.bin", new ByteArrayInputStream(c1));
        uploadService.uploadChunk(IDENT, 2, 3, "a.bin", new ByteArrayInputStream(c2));
        uploadService.uploadChunk(IDENT, 3, 3, "a.bin", new ByteArrayInputStream(c3));

        long total = c1.length + c2.length + c3.length;
        UploadMergeDTO dto = mergeDto(3, total, "a.bin");
        UploadMergeVO vo = uploadService.merge(dto);

        assertNotNull(vo.getFileKey(), "应返回文件标识");
        assertEquals(total, vo.getFileSize(), "文件大小应为各分片之和");
        assertEquals("a.bin", vo.getFileName());

        // 内容与按序拼接一致（验证合并顺序正确）
        byte[] expected = new byte[(int) total];
        System.arraycopy(c1, 0, expected, 0, c1.length);
        System.arraycopy(c2, 0, expected, c1.length, c2.length);
        System.arraycopy(c3, 0, expected, c1.length + c2.length, c3.length);
        try (var in = storageService.open(vo.getFileKey())) {
            assertArrayEquals(expected, in.readAllBytes(), "合并内容应按分片序号顺序拼接");
        }

        // 合并后临时分片立即清理（契约 §8.4：不残留中间文件）
        assertTrue(chunkStorage.uploadedChunks(IDENT).isEmpty(), "合并后不应残留分片");
    }

    /**
     * 缺失分片 → 2002 且提示缺失序号。
     *
     * <p>断言必须锁定**服务层聚合提示**（"缺少分片序号"），不能只断言"某个 2002"——
     * 存储层 {@code ChunkStorage.mergeTo} 也有一道 2002 兜底，
     * 若只断言 code，服务层的聚合逻辑被删掉也照样绿灯（缺陷注入已实证）。
     */
    @Test
    void missingChunkRejectedWith2002AndMissingNumbers() {
        uploadService.uploadChunk(IDENT, 1, 3, "a.bin", new ByteArrayInputStream(chunkBytes(1, 16)));
        uploadService.uploadChunk(IDENT, 3, 3, "a.bin", new ByteArrayInputStream(chunkBytes(3, 16)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.merge(mergeDto(3, 48, "a.bin")));
        assertEquals(Code.CHUNK_INVALID, ex.getCode(), "缺失分片应返回 2002");
        assertTrue(ex.getMessage().contains("缺少分片序号"),
                "应为服务层聚合提示（告知缺哪些片，前端据此续传），实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("2"), "提示应含缺失序号，实际：" + ex.getMessage());
    }

    /** 超过 maxFileSize → 2001 且 message 含当前上限 */
    @Test
    void exceedingMaxSizeRejectedWith2001() {
        // 把上限调小以模拟超限（不改生产默认值）
        settingService.putValue("maxFileSize", "10");

        uploadService.uploadChunk(IDENT, 1, 1, "a.bin", new ByteArrayInputStream(chunkBytes(1, 64)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.merge(mergeDto(1, 64, "a.bin")));
        assertEquals(Code.FILE_TOO_LARGE, ex.getCode(), "超上限应返回 2001");
        assertTrue(ex.getMessage().contains("10"), "message 应含当前上限，实际：" + ex.getMessage());
    }

    /** 分片序号越界 → 1003（1 ≤ n ≤ totalChunks） */
    @Test
    void chunkNumberOutOfRangeRejected() {
        byte[] data = chunkBytes(1, 8);
        BusinessException below = assertThrows(BusinessException.class,
                () -> uploadService.uploadChunk(IDENT, 0, 3, "a.bin", new ByteArrayInputStream(data)));
        assertEquals(Code.PARAM_INVALID, below.getCode(), "序号 0 应被拒");

        BusinessException above = assertThrows(BusinessException.class,
                () -> uploadService.uploadChunk(IDENT, 4, 3, "a.bin", new ByteArrayInputStream(data)));
        assertEquals(Code.PARAM_INVALID, above.getCode(), "序号超过总数应被拒");
    }

    /** 同一分片重传：覆盖而非报错（前端重试场景） */
    @Test
    void reuploadSameChunkOverwrites() throws IOException {
        byte[] first = chunkBytes(1, 16);
        uploadService.uploadChunk(IDENT, 1, 1, "a.bin", new ByteArrayInputStream(first));
        byte[] second = chunkBytes(9, 24);
        uploadService.uploadChunk(IDENT, 1, 1, "a.bin", new ByteArrayInputStream(second));

        UploadMergeVO vo = uploadService.merge(mergeDto(1, 24, "a.bin"));
        try (var in = storageService.open(vo.getFileKey())) {
            assertArrayEquals(second, in.readAllBytes(), "重传应覆盖旧分片");
        }
    }

    /** 取消上传：清理分片且幂等（重复取消不报错） */
    @Test
    void cancelCleansAndIsIdempotent() {
        uploadService.uploadChunk(IDENT, 1, 2, "a.bin", new ByteArrayInputStream(chunkBytes(1, 16)));
        uploadService.cancel(IDENT);
        assertTrue(chunkStorage.uploadedChunks(IDENT).isEmpty(), "取消后应无分片");

        uploadService.cancel(IDENT);          // 幂等
        uploadService.cancel("never-existed"); // 不存在也不报错
    }

    /** identifier 路径安全：含穿越构造一律拒绝（N-05 要求做路径安全校验） */
    @Test
    void identifierPathTraversalRejected() {
        byte[] data = chunkBytes(1, 8);
        for (String bad : List.of("..", "../escape", "a/b", "a\\b", "a\0b")) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> uploadService.uploadChunk(bad, 1, 1, "a.bin", new ByteArrayInputStream(data)),
                    "非法标识应被拒：" + bad);
            assertEquals(Code.PARAM_INVALID, ex.getCode(), "非法标识 → 1003：" + bad);
        }
    }

    /** 空 identifier → 1003 */
    @Test
    void blankIdentifierRejected() {
        assertThrows(BusinessException.class,
                () -> uploadService.uploadChunk("", 1, 1, "a.bin", new ByteArrayInputStream(chunkBytes(1, 8))));
        assertThrows(BusinessException.class,
                () -> uploadService.uploadChunk(null, 1, 1, "a.bin", new ByteArrayInputStream(chunkBytes(1, 8))));
    }

    /**
     * 大文件流式合并：内存不随体积线性增长。
     *
     * <p>用 200 片 × 64 KB（约 12.8 MB）验证合并可完成且内容正确；
     * 真正的 GB 级实测由人工在真机执行（沙箱不适合跑 GB 级）。
     */
    @Test
    void streamingMergeHandlesManyChunks() throws IOException {
        int chunks = 200;
        int size = 64 * 1024;
        for (int i = 1; i <= chunks; i++) {
            uploadService.uploadChunk(IDENT, i, chunks, "big.bin", new ByteArrayInputStream(chunkBytes(i, size)));
        }
        long total = (long) chunks * size;

        UploadMergeVO vo = uploadService.merge(mergeDto(chunks, total, "big.bin"));
        assertEquals(total, vo.getFileSize(), "大文件合并大小应正确");

        // 抽查首尾字节，确认拼接顺序未错
        try (var in = storageService.open(vo.getFileKey())) {
            byte[] head = in.readNBytes(4);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, head, "首片内容应来自第 1 片");
        }
        assertEquals(total, storageService.size(vo.getFileKey()));
        assertTrue(chunkStorage.uploadedChunks(IDENT).isEmpty(), "合并后分片应清空");
    }

    /** 声明大小与实际不一致（分片被截断）→ 2002，且不留半成品文件 */
    @Test
    void sizeMismatchRejectedAndNoPartialFileLeft() {
        uploadService.uploadChunk(IDENT, 1, 1, "a.bin", new ByteArrayInputStream(chunkBytes(1, 16)));
        // 声明 999 字节，实际只有 16 字节
        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.merge(mergeDto(1, 999, "a.bin")));
        assertEquals(Code.CHUNK_INVALID, ex.getCode(), "大小不一致应返回 2002");
    }

    // ---------------- 辅助 ----------------

    private UploadCheckDTO checkDto(int totalChunks) {
        UploadCheckDTO dto = new UploadCheckDTO();
        dto.setIdentifier(IDENT);
        dto.setFileName("a.bin");
        dto.setFileSize(1024L);
        dto.setTotalChunks(totalChunks);
        return dto;
    }

    private UploadMergeDTO mergeDto(int totalChunks, long fileSize, String fileName) {
        UploadMergeDTO dto = new UploadMergeDTO();
        dto.setIdentifier(IDENT);
        dto.setFileName(fileName);
        dto.setFileSize(fileSize);
        dto.setTotalChunks(totalChunks);
        return dto;
    }

    /** 合并后临时目录应为空（目录可能已被清理删除） */
    @Test
    void chunkDirEmptyAfterMerge() throws IOException {
        uploadService.uploadChunk(IDENT, 1, 1, "a.bin", new ByteArrayInputStream(chunkBytes(1, 8)));
        uploadService.merge(mergeDto(1, 8, "a.bin"));

        Path dir = chunkStorage.chunkRoot().resolve(IDENT);
        assertFalse(Files.exists(dir), "分片目录应已被清理");
    }

    /** 文件名一致性问题：merge 用不同 fileName 也应按 identifier 合并（identifier 才是归属键） */
    @Test
    void mergeUsesIdentifierAsOwnershipKey() {
        uploadService.uploadChunk(IDENT, 1, 1, "orig.bin", new ByteArrayInputStream(chunkBytes(1, 8)));
        UploadMergeVO vo = uploadService.merge(mergeDto(1, 8, "renamed.bin"));
        assertEquals("renamed.bin", vo.getFileName(), "返回的 fileName 取合并请求声明值");
    }

    /** UTF-8 中文文件名可正常参与上传（内容为二进制，名称仅作元数据） */
    @Test
    void chineseFileNameAccepted() {
        String cn = "工具包.zip";
        uploadService.uploadChunk(IDENT, 1, 1, cn, new ByteArrayInputStream(chunkBytes(1, 8)));
        UploadMergeVO vo = uploadService.merge(mergeDto(1, 8, cn));
        assertEquals(cn, vo.getFileName());
    }

    /** 临时目录里已存在非分片文件时不影响后续合并 */
    @Test
    void unrelatedFilesInChunkDirAreIgnored() throws IOException {
        uploadService.uploadChunk(IDENT, 1, 1, "a.bin", new ByteArrayInputStream(chunkBytes(1, 8)));
        Files.createFile(chunkStorage.dirFor(IDENT).resolve("noise.txt"));

        UploadCheckVO vo = uploadService.check(checkDto(1));
        assertEquals(List.of(1), vo.getUploadedChunks(), "只统计 .part 分片");

        uploadService.merge(mergeDto(1, 8, "a.bin"));
        assertFalse(Files.exists(chunkStorage.chunkRoot().resolve(IDENT)), "清理应连目录一起删");
    }

    /** UTF-8 内容完整性：中文文本分片合并后内容不损坏 */
    @Test
    void utf8ContentMergedIntact() throws IOException {
        String text = "工具管理箱-中文内容校验";
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        int half = all.length / 2;
        byte[] p1 = java.util.Arrays.copyOfRange(all, 0, half);
        byte[] p2 = java.util.Arrays.copyOfRange(all, half, all.length);

        uploadService.uploadChunk(IDENT, 1, 2, "c.txt", new ByteArrayInputStream(p1));
        uploadService.uploadChunk(IDENT, 2, 2, "c.txt", new ByteArrayInputStream(p2));
        UploadMergeVO vo = uploadService.merge(mergeDto(2, all.length, "c.txt"));

        try (var in = storageService.open(vo.getFileKey())) {
            assertEquals(text, new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "UTF-8 中文内容应完整");
        }
    }

    /**
     * C8 · 重复点击合并：**明确提示且不产生第二份文件**。
     *
     * <p>首次合并成功后分片即被清理，因此再次合并必然找不到分片 → 明确 2002
     * （而不是静默再生成一份产物）。同时校验首次产物未被误删、存储区未新增孤儿文件 ——
     * 后者是"重复点击不产生重复文件记录"的可观测证据。
     */
    @Test
    void repeatedMergeRejectedWith2002AndNoOrphanFile() throws IOException {
        uploadService.uploadChunk(IDENT, 1, 1, "a.bin", new ByteArrayInputStream(chunkBytes(1, 8)));
        UploadMergeVO first = uploadService.merge(mergeDto(1, 8, "a.bin"));
        assertNotNull(first.getFileKey(), "首次合并应产出文件标识");
        long storedBefore = storedFileCount();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.merge(mergeDto(1, 8, "a.bin")),
                "重复合并应被明确拒绝");
        assertEquals(Code.CHUNK_INVALID, ex.getCode(), "重复合并应返回 2002（分片已清理）");

        assertTrue(storageService.exists(first.getFileKey()), "首次产物不得被误删");
        try (var in = storageService.open(first.getFileKey())) {
            assertEquals(8, in.readAllBytes().length, "首次产物内容应完好");
        }
        assertEquals(storedBefore, storedFileCount(),
                "合并失败不得在存储区留下孤儿文件（重复点击不产生重复文件）");
    }

    /** 存储根目录下的正式文件数（分片临时目录不在其中） */
    private long storedFileCount() throws IOException {
        Path root = Path.of(storageProperties.getRoot());
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
