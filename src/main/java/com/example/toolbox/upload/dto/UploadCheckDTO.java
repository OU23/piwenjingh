package com.example.toolbox.upload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 上传前检查入参（契约 §8.1 UploadCheckDTO）。
 * 字段名不得改（契约 §八 明确要求）。
 */
@Data
public class UploadCheckDTO {

    /** 文件唯一标识（不透明字符串，后端不校验格式 —— N-05） */
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
