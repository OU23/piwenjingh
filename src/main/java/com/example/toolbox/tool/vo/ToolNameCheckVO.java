package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 同名检查出参（契约 §9.4 ToolNameCheckVO）。
 * canAppend 为 v1.2 新增（N-01）：同名工具是否在当前用户**可见范围内**。
 */
@Data
@Builder
public class ToolNameCheckVO {

    /** 是否已存在同名工具 */
    private Boolean existed;

    /** 已存在时返回其 ID，否则 null */
    private String toolId;

    /** 已存在时返回其最新版本号，否则 null */
    private String latestVersion;

    /**
     * 同名工具是否在当前用户可见范围内（本部门，或 scope = PUBLIC）。
     * true = 可追加版本；false = 名称被占用但不可见（前端提示改名、不允许提交）。
     * **无同名时为 false**（契约 §9.4）。
     */
    private Boolean canAppend;
}
