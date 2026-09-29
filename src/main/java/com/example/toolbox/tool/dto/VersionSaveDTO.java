package com.example.toolbox.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 追加版本入参（契约 §9.4 VersionSaveDTO）。
 * 版本号只校验长度与同一工具内不重复，**不做格式强校验**（N-08：不得强制 v 前缀）。
 */
@Data
public class VersionSaveDTO {

    @NotBlank(message = "版本号不能为空")
    @Size(max = 20, message = "版本号不能超过 20 字符")
    private String version;

    @NotBlank(message = "文件标识不能为空")
    private String fileKey;

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotBlank(message = "文件格式不能为空")
    @Size(max = 20, message = "文件格式不能超过 20 字符")
    private String fileExt;

    @NotNull(message = "文件大小不能为空")
    private Long fileSize;
}
