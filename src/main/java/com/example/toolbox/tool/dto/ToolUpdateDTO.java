package com.example.toolbox.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑工具元数据入参（契约 §9.4 ToolUpdateDTO，**v1.23 修订**）。
 *
 * <p>可改：{@code name}（本期前端不提供改名入口，恒回传原名）、{@code intro}、{@code readme}、
 * {@code platform}、{@code dependency}、{@code tagIds}、{@code scope}；{@code LINK} 类型另可改 {@code linkUrl}。
 *
 * <p><b>v1.23 三处变更</b>：
 * <ol>
 *   <li>{@code version}：**去掉 {@code @NotBlank}**，且服务端**忽略**（不再更新 {@code latest_version}）——
 *       编辑页不提供版本号，{@code LINK} 工具更是 {@code latest_version = ''}；</li>
 *   <li>{@code fileExt}：**去掉 {@code @NotBlank}**，服务端**忽略**（本期编辑不改格式）——
 *       {@code LINK} 工具 {@code file_ext = ''}，保留必填会让编辑链接工具 100% 返回 400；</li>
 *   <li>{@code linkUrl}：**新增，可选**，仅 {@code LINK} 可传（{@code FILE} 传了 → 1003）。</li>
 * </ol>
 *
 * <p>⚠️ 仍不含 {@code deptId} / {@code fileKey}：归属部门走独立接口 {@code PUT /api/tools/{id}/dept}，
 * 文件本身不因编辑而变。
 */
@Data
public class ToolUpdateDTO {

    @NotBlank(message = "工具名称不能为空")
    @Size(max = 100, message = "工具名称不能超过 100 字符")
    private String name;

    /**
     * 版本号：**已废弃（v1.23）**。
     *
     * <p>仅为**向后兼容**保留字段（老前端仍会回传）—— 服务端**忽略**，不修改
     * {@code tb_tool.latest_version}。版本号属于版本记录，只能经"追加新版本"变更。
     */
    @Size(max = 20, message = "版本号不能超过 20 字符")
    private String version;

    @NotBlank(message = "简介不能为空")
    @Size(max = 200, message = "简介不能超过 200 字符")
    private String intro;

    @Size(max = 10000, message = "使用说明不能超过 10000 字符")
    private String readme;

    /**
     * 文件格式：**v1.23 起非必填且被忽略**（本期编辑不改格式）。
     * 保留字段仅为兼容老前端回传，避免 400。
     */
    @Size(max = 20, message = "文件格式不能超过 20 字符")
    private String fileExt;

    @Size(max = 50, message = "运行平台不能超过 50 字符")
    private String platform;

    @Size(max = 500, message = "依赖说明不能超过 500 字符")
    private String dependency;

    private List<String> tagIds;

    @NotBlank(message = "可见范围不能为空")
    private String scope;

    /**
     * 链接地址（**v1.23 新增**，选填）：仅 {@code LINK} 类型可传，传了即覆盖
     * （链接工具不做版本历史，URL 改了直接覆盖，只记 {@code TOOL_UPDATE} 审计）；
     * {@code FILE} 类型传了 → 1003。协议白名单：仅 {@code http} / {@code https}，长度 ≤512。
     */
    @Size(max = 512, message = "链接地址不能超过 512 字符")
    private String linkUrl;
}
