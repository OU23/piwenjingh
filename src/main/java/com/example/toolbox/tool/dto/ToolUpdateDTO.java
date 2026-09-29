package com.example.toolbox.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑工具元数据入参（契约 §9.4 ToolUpdateDTO）。
 * 均可改；`version` 为工具**当前展示版本号**（非新增版本）。
 * ⚠️ 不含 deptId / fileKey —— 归属部门与文件不因编辑而变。
 */
@Data
public class ToolUpdateDTO {

    @NotBlank(message = "工具名称不能为空")
    @Size(max = 100, message = "工具名称不能超过 100 字符")
    private String name;

    @NotBlank(message = "版本号不能为空")
    @Size(max = 20, message = "版本号不能超过 20 字符")
    private String version;

    @NotBlank(message = "简介不能为空")
    @Size(max = 200, message = "简介不能超过 200 字符")
    private String intro;

    @Size(max = 10000, message = "使用说明不能超过 10000 字符")
    private String readme;

    @NotBlank(message = "文件格式不能为空")
    @Size(max = 20, message = "文件格式不能超过 20 字符")
    private String fileExt;

    @Size(max = 50, message = "运行平台不能超过 50 字符")
    private String platform;

    @Size(max = 500, message = "依赖说明不能超过 500 字符")
    private String dependency;

    private List<String> tagIds;

    @NotBlank(message = "可见范围不能为空")
    private String scope;
}
