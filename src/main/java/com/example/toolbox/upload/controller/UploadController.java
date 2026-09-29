package com.example.toolbox.upload.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.R;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.upload.dto.UploadCheckDTO;
import com.example.toolbox.upload.dto.UploadMergeDTO;
import com.example.toolbox.upload.service.UploadService;
import com.example.toolbox.upload.vo.UploadCheckVO;
import com.example.toolbox.upload.vo.UploadMergeVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传（契约 §八 /api/upload）。
 *
 * <p>⚠️ 分片上传的 multipart 字段名（{@code chunk} / {@code identifier} /
 * {@code chunkNumber} / {@code totalChunks} / {@code fileName}）**必须与契约完全一致**，
 * 不得改名（契约 §八 顶部明确要求）。
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    /** 上传前检查：返回已成功接收的分片序号（断点续传依据） */
    @PostMapping("/check")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<UploadCheckVO> check(@RequestBody @Valid UploadCheckDTO dto) {
        return R.ok(uploadService.check(dto));
    }

    /** 上传单个分片（字段名照契约 §8.1，不得改名） */
    @PostMapping("/chunk")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<Void> chunk(@RequestParam("chunk") MultipartFile chunk,
                         @RequestParam("identifier") String identifier,
                         @RequestParam("chunkNumber") int chunkNumber,
                         @RequestParam("totalChunks") int totalChunks,
                         @RequestParam("fileName") String fileName) throws java.io.IOException {
        try (var in = chunk.getInputStream()) {
            uploadService.uploadChunk(identifier, chunkNumber, totalChunks, fileName, in);
        }
        return R.ok();
    }

    /** 合并分片，返回文件标识 */
    @PostMapping("/merge")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<UploadMergeVO> merge(@RequestBody @Valid UploadMergeDTO dto) {
        return R.ok(uploadService.merge(dto));
    }

    /** 取消上传，清理已传分片（幂等） */
    @DeleteMapping("/{identifier}")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<Void> cancel(@PathVariable String identifier) {
        uploadService.cancel(identifier);
        return R.ok();
    }
}
