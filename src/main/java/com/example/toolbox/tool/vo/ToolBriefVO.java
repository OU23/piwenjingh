package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 授权候选 / 已授权工具简表（契约 §6.1 ToolBriefVO）：5 字段（v1.14 由 4 增 1）。
 * 不暴露 fileKey 等内部字段；latestVersion 取自 tb_tool 冗余列。
 */
@Data
@Builder
public class ToolBriefVO {

    /** 工具 ID（字符串，契约 §1.6） */
    private String id;

    /** 工具名称 */
    private String name;

    /** 归属部门名称 */
    private String deptName;

    /** 最新版本号 */
    private String latestVersion;

    /**
     * 可见范围：DEPT / PUBLIC（契约 §13.2；v1.14 新增）。
     * 供前端识别 PUBLIC 工具以收敛授权入口与候选列表（PUBLIC 工具无需授权）。
     */
    private String scope;
}
