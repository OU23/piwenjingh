package com.example.toolbox.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 直传完成登记入参（契约 v1.20 §8.7，M-23）。
 *
 * <p>直传无「合并」动作（分片由 OSS 自行合并），故本接口只做**复核**：
 * 前缀校验 → HEAD 对象（存在性 + 大小）→ 返回 {@code UploadMergeVO}。
 */
@Data
public class UploadDirectCompleteDTO {

    /** 上传时后端下发的对象键 */
    @NotBlank(message = "对象键不能为空")
    private String objectKey;

    /** 原始文件名（须与 credentials 时一致） */
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    /** 实际文件大小（须与 OSS 对象大小一致） */
    @NotNull(message = "文件大小不能为空")
    private Long fileSize;
}
