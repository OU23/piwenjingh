package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 同名检查出参（契约 §9.4 ToolNameCheckVO）。
 * v1.21：同名判定收窄到「当前上传人 + 未删除」；回收站里的同名工具由 inTrash 单独标识。
 */
@Data
@Builder
public class ToolNameCheckVO {

    /** 当前登录人是否已有**未删除**的同名工具 */
    private Boolean existed;

    /** 已存在时返回其 ID，否则 null */
    private String toolId;

    /** 已存在时返回其最新版本号，否则 null */
    private String latestVersion;

    /** 是否可追加为新版本（existed = true 时恒为 true） */
    private Boolean canAppend;

    /** 回收站里是否存在「同上传人 + 同名」的工具（tb_tool.deleted = 1） */
    private Boolean inTrash;

    /** inTrash = true 时返回该已删除工具的 ID，否则 null */
    private String trashToolId;
}
