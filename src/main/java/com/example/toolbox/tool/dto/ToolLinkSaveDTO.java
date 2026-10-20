package com.example.toolbox.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建**链接工具**入参（契约 v1.23 §6.1 ToolLinkSaveDTO）。
 *
 * <p>与 {@link ToolSaveDTO} 的差别：**没有版本号 / 文件 / 文件格式 / 文件大小**，
 * 而以 {@code linkUrl} 替代 —— 链接工具**不产生 {@code tb_tool_version} 记录**。
 *
 * <p>归属部门口径与文件工具完全一致：{@code deptId} 选填，**仅持 {@code tool:assign} 时生效**，
 * 否则取登录用户部门。
 */
@Data
public class ToolLinkSaveDTO {

    @NotBlank(message = "工具名称不能为空")
    @Size(max = 100, message = "工具名称不能超过 100 字符")
    private String name;

    /** 链接地址：必填，仅 http / https，≤512（服务端仍做协议白名单二次校验） */
    @NotBlank(message = "链接地址不能为空")
    @Size(max = 512, message = "链接地址不能超过 512 字符")
    private String linkUrl;

    @NotBlank(message = "简介不能为空")
    @Size(max = 200, message = "简介不能超过 200 字符")
    private String intro;

    /** 使用说明（纯文本），选填 */
    @Size(max = 10000, message = "使用说明不能超过 10000 字符")
    private String readme;

    @Size(max = 50, message = "运行平台不能超过 50 字符")
    private String platform;

    @Size(max = 500, message = "依赖说明不能超过 500 字符")
    private String dependency;

    /** 标签 ID 列表，选填 */
    private List<String> tagIds;

    @NotBlank(message = "可见范围不能为空")
    private String scope;

    /** 归属部门（选填）：仅持 {@code tool:assign} 时生效；不传 = 当前用户部门 */
    private String deptId;
}
