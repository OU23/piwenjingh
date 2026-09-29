package com.example.toolbox.upload;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.DownloadService;
import com.example.toolbox.upload.dto.UploadMergeDTO;
import com.example.toolbox.upload.service.ChunkStorage;
import com.example.toolbox.upload.service.UploadService;
import com.example.toolbox.upload.vo.UploadMergeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大文件流式实测台（B3 计划 T10 末条：「3 GB 文件合并与下载，内存占用不随体积线性增长，人工判定」）。
 *
 * <p><b>为何默认 {@code @Disabled}</b>：默认体积 512 MB，不宜进日常门禁。需要时手动执行，
 * 或用系统属性把体积抬到计划要求的 3 GB 口径：
 *
 * <pre>
 * # 512 MB（受限堆，验证"不整文件入内存"）
 * mvn test -Dtest=LargeFileStreamTest -DargLine="-Xmx256m"
 *
 * # 3 GB（计划要求口径；执行前确认磁盘可用空间 ≥ 8 GB）
 * mvn test -Dtest=LargeFileStreamTest -DargLine="-Xmx256m" \
 *          -DlargeFile.totalMb=3072 -DlargeFile.chunkMb=16
 * </pre>
 *
 * <p><b>判定依据</b>：分片落盘、合并（分片 → 正式存储）、下载（存储 → 响应流）全程**逐块流式**
 * （8 KB 缓冲），因此**文件体积可远大于堆上限**。用例开头即断言
 * {@code maxMemory < fileSize}——若实现退化为"整文件读入内存"，此处会直接
 * {@link OutOfMemoryError}。它验证的是**内存上界**，而非"能不能跑通"；
 * 同时校验合并大小与下载字节数，防止"流式但丢数据"。
 *
 * <p><b>数据来源</b>：分片内容由合成流按需生成（不预分配整文件）。
 * 用例结束清理临时分片与存储文件，不留大文件占盘。
 */
@Disabled("大文件实测台：默认不参与 mvn test，按类注释中的命令手动执行")
class LargeFileStreamTest extends IntegrationTestBase {

    @Autowired
    private UploadService uploadService;
    @Autowired
    private ChunkStorage chunkStorage;
    @Autowired
    private StorageService storageService;
    @Autowired
    private DownloadService downloadService;

    private static final String IDENT = "large-file-stream-probe";

    /** 总体积（MB），默认 512；3 GB 口径：{@code -DlargeFile.totalMb=3072} */
    private static final int TOTAL_MB = Integer.getInteger("largeFile.totalMb", 512);
    /** 单片体积（MB），默认 8 */
    private static final int CHUNK_MB = Integer.getInteger("largeFile.chunkMb", 8);

    @BeforeEach
    void setUpOperator() {
        loginAs(999_999L, "admin", 1L, PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);
    }

    /** 合并 → 下载：受限堆下不 OOM，且字节数一致 */
    @Test
    void largeFileMergeAndDownloadStreamWithoutLoadingWholeFile() throws Exception {
        int chunkSize = CHUNK_MB * 1024 * 1024;
        int totalChunks = (TOTAL_MB * 1024 * 1024) / chunkSize;
        long totalBytes = (long) totalChunks * chunkSize;
        long heapLimitMb = Runtime.getRuntime().maxMemory() >> 20;
        System.out.println("==== 大文件实测：总体积 " + TOTAL_MB + " MB，分片 " + totalChunks
                + " × " + CHUNK_MB + " MB，堆上限 " + heapLimitMb + " MB ====");
        assertTrue(Runtime.getRuntime().maxMemory() < totalBytes,
                "用例前提是「文件体积 > 堆上限」，否则证明不了流式；请调大 -DlargeFile.totalMb");

        // ① 分片上传：内容由合成流按需生成，不预分配整文件
        long t0 = System.currentTimeMillis();
        for (int seq = 1; seq <= totalChunks; seq++) {
            uploadService.uploadChunk(IDENT, seq, totalChunks, "big.bin",
                    new SyntheticStream(chunkSize, (byte) seq));
        }
        long t1 = System.currentTimeMillis();
        System.out.println("① 分片上传完成：" + (t1 - t0) + " ms");

        // ② 合并：服务层 → 管道 → ChunkStorage → 存储层，全程流式
        UploadMergeDTO dto = new UploadMergeDTO();
        dto.setIdentifier(IDENT);
        dto.setFileName("big.bin");
        dto.setFileSize(totalBytes);
        dto.setTotalChunks(totalChunks);
        UploadMergeVO merged = uploadService.merge(dto);
        long t2 = System.currentTimeMillis();
        System.out.println("② 合并完成：" + (t2 - t1) + " ms，fileSize = " + merged.getFileSize());
        assertEquals(totalBytes, merged.getFileSize(), "合并后大小应与声明一致");
        assertTrue(chunkStorage.uploadedChunks(IDENT).isEmpty(), "合并后临时分片应清空");

        // ③ 下载：登记工具与版本，走真实的 DownloadService（skipFully / LimitedInputStream 同一路径）
        Long toolId = registerToolAndVersion(merged.getFileKey(), totalBytes);
        LoginUser user = UserContext.get();
        DownloadService.DownloadPlan plan = downloadService.plan(toolId, null, user, false);
        assertEquals(totalBytes, plan.size(), "下载计划里的文件大小应正确");

        long downloaded = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = downloadService.openStream(plan)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                downloaded += read;
            }
        }
        long t3 = System.currentTimeMillis();
        System.out.println("③ 下载流读取完成：" + (t3 - t2) + " ms，共 " + downloaded + " 字节");
        assertEquals(totalBytes, downloaded, "下载字节数应与源文件一致");
        System.out.println("==== 实测通过：文件 " + TOTAL_MB + " MB > 堆上限 " + heapLimitMb
                + " MB，全程未 OOM（未整文件入内存）====");

        // ④ 清理：删掉这份大文件，避免占盘
        assertTrue(storageService.exists(merged.getFileKey()), "清理前文件应存在");
        storageService.delete(merged.getFileKey());
    }

    /** 登记工具 + 版本（复用产物），使下载可走正式鉴权链路 */
    private Long registerToolAndVersion(String fileKey, long size) {
        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName("大文件实测工具");
        tool.setIntro("LargeFileStreamTest 专用");
        tool.setFileExt("bin");
        tool.setScope(Scope.DEPT);
        tool.setDeptId(1L);
        tool.setUploaderId(999_999L);
        tool.setLatestVersion("v1.0");
        tool.setFileSize(size);
        tool.setDownloadCount(0);
        tool.setDeleted(0);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);

        ToolVersionDO v = new ToolVersionDO();
        v.setToolId(tool.getId());
        v.setVersion("v1.0");
        v.setFileKey(fileKey);
        v.setFileName("big.bin");
        v.setFileExt("bin");
        v.setFileSize(size);
        v.setUploaderId(999_999L);
        v.setDownloadCount(0);
        v.setCreateTime(now);
        toolVersionMapper.insert(v);

        tool.setLatestVersionId(v.getId());
        toolMapper.updateById(tool);
        return tool.getId();
    }

    /** 合成输入流：按需生成字节，仅占用调用方传入的缓冲区 */
    private static final class SyntheticStream extends InputStream {

        private final long size;
        private final byte seed;
        private long produced;

        SyntheticStream(long size, byte seed) {
            this.size = size;
            this.seed = seed;
        }

        @Override
        public int read() {
            if (produced >= size) {
                return -1;
            }
            produced++;
            return (seed + (int) (produced % 251)) & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (produced >= size) {
                return -1;
            }
            int n = (int) Math.min(len, size - produced);
            for (int i = 0; i < n; i++) {
                b[off + i] = (byte) ((seed + (int) ((produced + i) % 251)) & 0xFF);
            }
            produced += n;
            return n;
        }

        @Override
        public long skip(long n) {
            long s = Math.min(n, size - produced);
            produced += s;
            return s;
        }
    }
}
