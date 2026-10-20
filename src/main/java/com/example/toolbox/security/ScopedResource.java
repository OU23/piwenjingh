package com.example.toolbox.security;

/**
 * 可判定的资源可见属性。
 * 工具实体（B3）实现本接口即可接入统一可见范围判定，无需把 ToolDO 依赖引入 security 包。
 * 设计意图：让"可见范围判定"成为**物理上的唯一入口**（principles.md R3），
 * 而不是散落在各 Service 的 if-else 里。
 */
public interface ScopedResource {

    /** 可见范围：DEPT（本部门可见）/ PUBLIC（全员可见） */
    String getScope();

    /** 归属部门 ID */
    Long getDeptId();

    /** 资源 ID（工具 ID）；供定向授权等扩展判定引用，避免 security 包依赖具体实体 */
    Long getId();

    /**
     * 资源上传人 ID（**M-25 / v1.23 新增**）。
     *
     * <p>用于「上传人恒可见可管」规则（契约 §2.2 判定顺序第 5 条）：
     * 工具被调整到别的部门后，上传人仍能看到 / 编辑 / 删除它。
     * 允许返回 {@code null}（视为无上传人，不参与该条判定）。
     */
    Long getUploaderId();
}
