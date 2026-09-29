package com.example.toolbox.excel;

import java.util.List;

/**
 * 一行 Excel 数据。
 *
 * @param rowNum **Excel 实际行号**（表头为第 1 行，数据行自 2 起）——
 *               错误明细里的行号必须与用户在 Excel 中看到的那一行一致（契约 §6.2）
 * @param cells  按列序存放的文本值（已 trim；缺失列以空串补齐）
 */
public record XlsxRow(int rowNum, List<String> cells) {

    /** 取第 {@code index} 列（0-based）；越界或为 null 返回空串 */
    public String cell(int index) {
        if (index < 0 || index >= cells.size()) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value;
    }

    /** 整行是否全空（用于"全空行不计入 total"判定） */
    public boolean isBlank() {
        return cells.stream().allMatch(c -> c == null || c.isBlank());
    }
}
