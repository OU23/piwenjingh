package com.example.toolbox.excel;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 导入结果（契约 §6.2 `ImportResultVO`）。
 *
 * <p>核心口径：**「跳过」≠「失败」** —— 已存在计入 {@code skipCount} 且**不进错误 Excel**；
 * 只有校验不通过才计入 {@code failCount} 并出现在 {@code errors} / 错误文件里。
 */
@Data
@Builder
public class ImportResultVO {

    /** 参与处理的数据行数（**已排除表头行与全空行**） */
    private int total;

    /** 成功入库行数 */
    private int successCount;

    /** 跳过行数（系统中已存在；**不算失败**） */
    private int skipCount;

    /** 失败行数（校验不通过） */
    private int failCount;

    /** 本次**自动创建**的部门名称（仅用户导入可能非空） */
    private List<String> createdDeptNames;

    /** 失败明细（**最多前 {@link ExcelConstants#MAX_ERRORS} 条**） */
    private List<ImportErrorVO> errors;

    /** 有失败行时为错误 Excel 的 Base64（xlsx 字节）；无失败行时为 {@code null} */
    private String errorFileBase64;

    /** 错误 Excel 的建议文件名；无失败行时为 {@code null} */
    private String errorFileName;
}
