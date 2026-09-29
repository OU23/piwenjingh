package com.example.toolbox.common.constant;

/**
 * 通用状态取值（契约 §13.3 / 数据模型 §5.2）。禁止裸数字（0 / 1）出现在业务逻辑中。
 */
public final class StatusValue {

    /** 启用 */
    public static final int ENABLED = 1;

    /** 停用 */
    public static final int DISABLED = 0;

    /** 是否合法状态（只接受 1 / 0） */
    public static boolean isValid(Integer status) {
        return status != null && (status == ENABLED || status == DISABLED);
    }

    private StatusValue() {
    }
}
