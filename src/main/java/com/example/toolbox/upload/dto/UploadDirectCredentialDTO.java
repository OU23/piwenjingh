package com.example.toolbox.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 直传凭证入参（契约 v1.20 §8.6，M-23）。
 *
 * <p>★ **没有 partSize 入参**：分片大小**只由全局设置决定**（单一事实来源），
 * 前端不得自定义 —— 避免"设置页一个值、上传时另一个值"的混乱。
 */
@Data
public class UploadDirectCredentialDTO {

    /** 原始文件名（含扩展名，用于推导对象键后缀） */
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    /** 文件总大小（字节，**声明值**）；用于上限校验与分片数计算 */
    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    /**
     * 续传时回传上次下发的对象键（凭证过期续传 / 断点续传）；
     * 首次上传不传。带值时**先校验前缀与形态**，不通过 → 1003。
     */
    private String objectKey;
}
