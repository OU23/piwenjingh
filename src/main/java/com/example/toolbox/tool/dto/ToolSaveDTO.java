package com.example.toolbox.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建工具入参（契约 §9.4 ToolSaveDTO）。含首个版本信息。
 * 约束照契约：name ≤100、version ≤20、intro ≤200、readme ≤10000、dependency ≤500。
 * ⚠️ 不含 deptId —— 归属部门取当前登录用户（契约 §9.4）。
 */
@Data
public class ToolSaveDTO {

    @NotBlank(message = "工具名称不能为空")
    @Size(max = 100, message = "工具名称不能超过 100 字符")
    private String name;

    @NotBlank(message = "版本号不能为空")
    @Size(max = 20, message = "版本号不能超过 20 字符")
    private String version;

    @NotBlank(message = "简介不能为空")
    @Size(max = 200, message = "简介不能超过 200 字符")
    private String intro;

    /** 使用说明（纯文本），选填 */
    @Size(max = 10000, message = "使用说明不能超过 10000 字符")
    private String readme;

    @NotBlank(message = "文件格式不能为空")
    @Size(max = 20, message = "文件格式不能超过 20 字符")
    private String fileExt;

    @Size(max = 50, message = "运行平台不能超过 50 字符")
    private String platform;

    @Size(max = 500, message = "依赖说明不能超过 500 字符")
    private String dependency;

    /** 标签 ID 列表，选填 */
    private List<String> tagIds;

    @NotBlank(message = "可见范围不能为空")
    private String scope;

    @NotBlank(message = "文件标识不能为空")
    private String fileKey;

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotNull(message = "文件大小不能为空")
    private Long fileSize;
}
