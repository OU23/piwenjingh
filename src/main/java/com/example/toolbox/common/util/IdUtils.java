package com.example.toolbox.common.util;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;

/**
 * 对外 ID 为字符串，入参解析为 Long 的统一入口。
 * 格式非法 → 1003（参数校验失败），而非 500。
 */
public final class IdUtils {

    public static Long parse(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(Code.PARAM_INVALID, fieldName + "不能为空");
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(Code.PARAM_INVALID, fieldName + "格式非法");
        }
    }

    /** 对外输出：Long → String */
    public static String toStr(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    /**
     * 批量解析对外 ID 列表（批量接口共用）。
     *
     * <p>规则：**列表为空 → 1003**（契约 §9.6：`ids` 为空 → `1003`）；
     * 逐项解析（任一项格式非法即整请求 1003，**不静默丢弃** —— 静默丢弃会让用户以为删掉了）；
     * **去重**（同一 ID 传两次不该被处理两次）。
     */
    public static java.util.List<Long> parseAll(java.util.List<String> rawList, String fieldName) {
        if (rawList == null || rawList.isEmpty()) {
            throw new BusinessException(Code.PARAM_INVALID, fieldName + "列表不能为空");
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String raw : rawList) {
            Long id = parse(raw, fieldName);
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private IdUtils() {
    }
}
