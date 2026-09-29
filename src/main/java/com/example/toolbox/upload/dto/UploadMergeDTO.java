package com.example.toolbox.upload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 合并分片入参（契约 §8.1 UploadMergeDTO）。
 * ⚠️ 契约 §8.1 表格未列校验细节，但 §8.3 规定 fileName 在 check/chunk/merge
 * **三处必须完全一致**，故此处必须接收并校验。
 */
@Data
public class UploadMergeDTO {

    @NotBlank(message = "文件标识不能为空")
    private String identifier;

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotNull(message = "文件大小不能为空")
    @Min(value = 0, message = "文件大小不能为负")
    private Long fileSize;

    @NotNull(message = "分片总数不能为空")
    @Min(value = 1, message = "分片总数至少为 1")
    private Integer totalChunks;
}
