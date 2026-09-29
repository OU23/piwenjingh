package com.example.toolbox.common.constant;

/**
 * 工具可见范围（契约 §13.2 / 数据模型 §5.1）：仅两档。
 */
public final class Scope {

    /** 本部门可见 */
    public static final String DEPT = "DEPT";
    /** 全员可见 */
    public static final String PUBLIC = "PUBLIC";

    private Scope() {
    }
}
