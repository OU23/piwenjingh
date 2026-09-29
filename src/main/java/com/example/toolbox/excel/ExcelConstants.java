package com.example.toolbox.excel;

import java.util.List;

/**
 * Excel 导入导出公共常量（契约 §6.2）。
 * 表头文字即模板口径，**不得改动**（解析按列序号读取，但模板文字漂移会误导用户）。
 */
public final class ExcelConstants {

    /** 仅接受 `.xlsx`（`.xls` / `.csv` 不支持） */
    public static final String XLSX_EXT = ".xlsx";

    /** 上传体积上限：10 MB（超出 → 1003 整体失败） */
    public static final long MAX_IMPORT_BYTES = 10L * 1024 * 1024;

    /** `errors` 明细最多返回条数（超出部分只体现在错误 Excel 中） */
    public static final int MAX_ERRORS = 100;

    /** xlsx 的 MIME（模板 / 导出响应） */
    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // ---------------- 部门 ----------------

    public static final String DEPT_SHEET = "部门";
    public static final List<String> DEPT_HEAD = List.of("部门名称", "备注");
    public static final String DEPT_TEMPLATE_FILE = "部门导入模板.xlsx";
    public static final String DEPT_ERROR_FILE_PREFIX = "部门导入失败明细_";
    /** 导出文件名前缀（后接时间戳） */
    public static final String DEPT_EXPORT_PREFIX = "部门列表_";

    /** 部门模板示例行（提示用户正式导入前删除） */
    public static final List<String> DEPT_SAMPLE_ROW = List.of("研发部", "（示例行，正式导入前请删除）");

    // ---------------- 用户 ----------------

    public static final String USER_SHEET = "用户";
    public static final List<String> USER_HEAD = List.of("账号", "姓名", "部门名称", "初始密码");
    public static final String USER_TEMPLATE_FILE = "用户导入模板.xlsx";
    public static final String USER_ERROR_FILE_PREFIX = "用户导入失败明细_";
    /** 导出文件名前缀（后接时间戳） */
    public static final String USER_EXPORT_PREFIX = "用户列表_";

    /** 用户模板示例行（提示用户正式导入前删除） */
    public static final List<String> USER_SAMPLE_ROW =
            List.of("zhangsan", "张三", "研发部", "123456");

    // ---------------- 错误明细 ----------------

    public static final String ERROR_SHEET = "错误明细";
    public static final String ERROR_COLUMN = "错误原因";

    private ExcelConstants() {
    }
}
