package com.example.toolbox.upload.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.upload.dto.UploadCheckDTO;
import com.example.toolbox.upload.dto.UploadMergeDTO;
import com.example.toolbox.upload.vo.UploadCheckVO;
import com.example.toolbox.upload.vo.UploadMergeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;

/**
 * 分片上传服务（契约 §八）：检查 → 传分片 → 合并 → 取消。
 *
 * <p>关键约束：
 * <ul>
 *   <li>**R4 流式**：合并不把整文件读入内存（{@link ChunkStorage#mergeTo} 逐片流式拷贝）；</li>
 *   <li>**R1 存储隔离**：合并完成后仅把 {@code fileKey} 交给存储层，业务不碰物理路径；</li>
 *   <li>**N-05**：不校验 {@code identifier} 业务格式，仅做路径安全校验；</li>
 *   <li>**N-06**：不校验单片大小；接受并发与乱序。</li>
 * </ul>
 *
 * <p><b>注意</b>：合并本身**不在事务中**（原则 8：禁止在事务里做文件 IO）。
 * 文件落盘完成后，才由 {@code ToolService} 在事务内落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private final ChunkStorage chunkStorage;
    private final StorageService storageService;
    private final SettingService settingService;
    private final AuditLogger auditLogger;

    /** 上传前检查：返回已成功接收的分片序号（断点续传依据） */
    public UploadCheckVO check(UploadCheckDTO dto) {
        // fileName 参与一致性口径（契约 §8.3），但检查阶段只做归属查询，不落库
        List<Integer> uploaded = chunkStorage.uploadedChunks(dto.getIdentifier());
        return UploadCheckVO.builder().uploadedChunks(uploaded).build();
    }

    /** 上传单个分片（支持并发与乱序；同一分片重传即覆盖） */
    public void uploadChunk(String identifier, int chunkNumber, int totalChunks,
                            String fileName, InputStream data) {
        // chunkNumber 范围校验（1 ≤ n ≤ totalChunks）
        if (chunkNumber < 1 || chunkNumber > totalChunks) {
            throw new BusinessException(Code.PARAM_INVALID,
                    "分片序号超出范围：应在 1 ~ " + totalChunks + " 之间");
        }
        // ⚠️ 按 N-06 不校验单片大小 —— 分片大小由前端决定
        chunkStorage.saveChunk(identifier, chunkNumber, data);
    }

    /**
     * 合并分片（★）。
     *
     * <p>顺序：① 大小预校验 → ② 流式合并落盘 → ③ 清理临时分片 → ④ 返回文件标识。
     * 任一步失败都不留下"半合并"的正式文件（合并写入失败时由存储层清理目标文件）。
     */
    public UploadMergeVO merge(UploadMergeDTO dto) {
        long maxFileSize = settingService.getMaxFileSize();

        // ① 大小上限校验（契约 §8.4：超上限 → 2001，message 含当前上限）
        if (dto.getFileSize() != null && dto.getFileSize() > maxFileSize) {
            throw new BusinessException(Code.FILE_TOO_LARGE,
                    "文件超过大小上限（当前上限 " + maxFileSize + " 字节）");
        }

        // ② 分片齐全性校验：先查已传序号，缺失即刻报 2002 并提示缺失序号
        List<Integer> uploaded = chunkStorage.uploadedChunks(dto.getIdentifier());
        List<Integer> missing = findMissing(uploaded, dto.getTotalChunks());
        if (!missing.isEmpty()) {
            throw new BusinessException(Code.CHUNK_INVALID,
                    "分片不完整，缺少分片序号：" + joinNumbers(missing));
        }

        // ③ 流式合并：把分片按序写入存储层。
        //    用管道把「逐片读」接到「存储层写入」，全程不缓存整文件（R4）。
        String fileKey = storageService.generateKey();
        long written = streamMergeIntoStorage(fileKey, dto.getIdentifier(), dto.getTotalChunks());

        // ④ 落盘结果校验：实际写入大小与声明不一致视为分片异常
        if (dto.getFileSize() != null && dto.getFileSize() > 0 && written != dto.getFileSize()) {
            safeDelete(fileKey);
            throw new BusinessException(Code.CHUNK_INVALID,
                    "分片合并结果与声明大小不一致（实际 " + written + "，声明 " + dto.getFileSize() + "）");
        }

        // ⑤ 合并成功 → 立即清理该次上传的全部分片（契约 §8.4：不残留中间文件）
        chunkStorage.clean(dto.getIdentifier());

        String operator = currentOperator();
        auditLogger.write(operator, "UPLOAD_MERGE", "fileKey:" + fileKey, "OK");

        return UploadMergeVO.builder()
                .fileKey(fileKey)
                .fileName(dto.getFileName())
                .fileSize(written)
                .build();
    }

    /** 取消上传：清理已传分片；重复取消不报错（幂等） */
    public void cancel(String identifier) {
        chunkStorage.clean(identifier);
        auditLogger.write(currentOperator(), "UPLOAD_CANCEL", "identifier:" + identifier, "OK");
    }

    // ---------------- 内部 ----------------

    /**
     * 流式合并到存储层：用管道把分片读取端与存储写入端对接。
     *
     * <p>在独立线程中执行分片读取，主线程执行存储写入 —— 两端都是流式的，
     * 内存占用与文件总大小无关（仅 8 KB 缓冲 + 管道缓冲）。
     */
    private long streamMergeIntoStorage(String fileKey, String identifier, int totalChunks) {
        try (PipedInputStream pipeIn = new PipedInputStream(64 * 1024);
             PipedOutputStream pipeOut = new PipedOutputStream(pipeIn)) {

            final long[] written = new long[1];
            final Throwable[] failure = new Throwable[1];

            Thread producer = new Thread(() -> {
                try {
                    written[0] = chunkStorage.mergeTo(identifier, totalChunks, pipeOut);
                } catch (Throwable t) {
                    failure[0] = t;
                } finally {
                    try {
                        pipeOut.close();
                    } catch (IOException ignored) {
                        // 关闭失败不影响主流程
                    }
                }
            }, "chunk-merge-" + identifier);
            producer.setDaemon(true);
            producer.start();

            try {
                // 存储层流式写入（size 传 -1 表示由流长度决定）
                storageService.save(fileKey, pipeIn, -1);
            } finally {
                try {
                    producer.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    safeDelete(fileKey);
                    throw new BusinessException(Code.SYS_ERROR, "合并被中断");
                }
            }

            if (failure[0] != null) {
                safeDelete(fileKey);
                if (failure[0] instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException(Code.CHUNK_INVALID,
                        "分片合并失败：" + failure[0].getMessage());
            }
            return written[0];
        } catch (IOException e) {
            safeDelete(fileKey);
            throw new BusinessException(Code.STORAGE_UNAVAILABLE,
                    "存储写入失败：" + e.getMessage());
        }
    }

    private List<Integer> findMissing(List<Integer> uploaded, int totalChunks) {
        List<Integer> missing = new java.util.ArrayList<>();
        java.util.Set<Integer> present = new java.util.HashSet<>(uploaded);
        for (int i = 1; i <= totalChunks; i++) {
            if (!present.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    /** 缺失序号提示：连续区间折叠，避免 1 万个分片时提示串过长 */
    private String joinNumbers(List<Integer> nums) {
        if (nums.size() > 20) {
            return nums.subList(0, 20).toString() + " …（共 " + nums.size() + " 个缺失）";
        }
        return nums.toString();
    }

    private void safeDelete(String fileKey) {
        try {
            storageService.delete(fileKey);
        } catch (IOException e) {
            log.warn("[MERGE] 清理半成品文件失败 fileKey={} err={}", fileKey, e.getMessage());
        }
    }

    private String currentOperator() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getUsername();
    }
}
