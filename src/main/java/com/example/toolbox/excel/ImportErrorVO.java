package com.example.toolbox.excel;

/**
 * 导入失败明细（契约 §6.2 `ImportError`）。
 *
 * @param rowNum  **Excel 实际行号**（用户在 Excel 中看到的那一行）
 * @param message 面向用户的中文失败原因
 */
public record ImportErrorVO(int rowNum, String message) {
}
