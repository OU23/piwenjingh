package com.example.toolbox.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.toolbox.security.ScopedResource;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具表（tb_tool，契约 §9 / 数据模型 §3.5）。
 *
 * <p>实现 {@link ScopedResource}，从而可直接接入 B2 的
 * {@code ScopeChecker} 可见范围唯一入口（R3），无需在业务层重写判定。
 */
@Data
@TableName("tb_tool")
public class ToolDO implements ScopedResource {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具名称，**在「上传人维度」内唯一**（v1.21 / 数据模型 v1.4；不同上传人可同名并存） */
    private String name;

    /** 简介，≤200 */
    private String intro;

    /** 使用说明（纯文本，保留换行） */
    private String readme;

    /** 文件格式（小写，不含点号） */
    private String fileExt;

    /** 运行平台 */
    private String platform;

    /** 依赖说明 */
    private String dependency;

    /** 可见范围：DEPT / PUBLIC（契约 §13.2） */
    private String scope;

    /**
     * 工具类型（**M-25 / v1.23 新增**，数据模型 v1.6）：{@code FILE} 文件工具 / {@code LINK} 链接工具。
     * 存量数据由 DB 默认值 {@code FILE} 兜底，应用层用 {@code ToolType.of()} 再归一化一次。
     */
    private String toolType;

    /** 链接工具地址（**M-25 新增**）：仅 {@code tool_type = LINK} 时有值，{@code http/https}，≤512 */
    private String linkUrl;

    /**
     * 归属部门。
     *
     * <p>默认取**上传人所属部门**；**v1.23 起**持 {@code tool:assign} 者可经
     * {@code PUT /api/tools/{id}/dept} 调整为任意启用部门，语义扩展为「当前归属部门」。
     */
    private Long deptId;

    /** 上传人 */
    private Long uploaderId;

    /** 最新版本 ID */
    private Long latestVersionId;

    /** 最新版本号（冗余，供列表展示） */
    private String latestVersion;

    /** 最新版本文件大小（冗余） */
    private Long fileSize;

    /** 累计下载次数 */
    private Integer downloadCount;

    /** 软删除标记：0 正常 / 1 已删除 */
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
