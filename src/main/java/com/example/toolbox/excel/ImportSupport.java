package com.example.toolbox.excel;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入公共支撑（契约 §6.2）：文件级校验、DTO 规则复用校验、错误 Excel 生成、结果截断。
 * 两个域（部门 / 用户）共用，避免各写一套。
 */
public final class ImportSupport {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ImportSupport() {
    }

    /**
     * 文件级校验（**整体失败**，不是逐行失败）：未选文件 / 非 `.xlsx` / 超 10 MB → {@code 1003}。
     */
    public static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(Code.PARAM_INVALID, "请选择要导入的文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(ExcelConstants.XLSX_EXT)) {
            throw new BusinessException(Code.PARAM_INVALID, "仅支持 .xlsx 格式");
        }
        if (file.getSize() > ExcelConstants.MAX_IMPORT_BYTES) {
            throw new BusinessException(Code.PARAM_INVALID, "文件超过 10MB 上限");
        }
    }

    /**
     * 用**既有 DTO 的校验注解**校验（复用规则，不另写一套 if-else）。
     *
     * @return 通过返回 {@code null}；否则返回首条面向用户的中文消息
     */
    public static String validateDto(Validator validator, Object dto) {
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        if (violations.isEmpty()) {
            return null;
        }
        return violations.iterator().next().getMessage();
    }

    /** 截断错误明细（契约：`errors` 最多前 100 条） */
    public static List<ImportErrorVO> limitErrors(List<ImportErrorVO> errors) {
        if (errors.size() <= ExcelConstants.MAX_ERRORS) {
            return errors;
        }
        return new ArrayList<>(errors.subList(0, ExcelConstants.MAX_ERRORS));
    }

    /**
     * 生成错误 Excel（列 = **原模板列 + 末列「错误原因」**，行 = 用户填写原文 + 原因）。
     *
     * @return Base64 编码的 xlsx 字节
     */
    public static String buildErrorFileBase64(List<String> templateHead,
                                              List<XlsxRow> failedRows,
                                              List<ImportErrorVO> errors) {
        List<List<String>> head = new ArrayList<>(XlsxWriter.headOf(templateHead));
        head.add(List.of(ExcelConstants.ERROR_COLUMN));

        Map<Integer, String> reasonByRow = new HashMap<>();
        for (ImportErrorVO error : errors) {
            reasonByRow.putIfAbsent(error.rowNum(), error.message());
        }

        List<List<String>> rows = new ArrayList<>(failedRows.size());
        for (XlsxRow row : failedRows) {
            List<String> cells = new ArrayList<>(row.cells());
            cells.add(reasonByRow.getOrDefault(row.rowNum(), ""));
            rows.add(cells);
        }
        byte[] bytes = XlsxWriter.toBytes(ExcelConstants.ERROR_SHEET, head, rows);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 错误 Excel 建议文件名：{@code <前缀>yyyyMMdd_HHmmss.xlsx} */
    public static String errorFileName(String prefix) {
        return timestampedFileName(prefix);
    }

    /** 带时间戳的 xlsx 文件名：{@code <前缀>yyyyMMdd_HHmmss.xlsx}（错误明细 / 导出共用） */
    public static String timestampedFileName(String prefix) {
        return prefix + LocalDateTime.now().format(TIMESTAMP) + ExcelConstants.XLSX_EXT;
    }

    /** 空安全取值（导出用） */
    public static String nz(String value) {
        return value == null ? "" : value;
    }

    /** 空白 → {@code null}（选填字段留空时不落空串） */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 状态导出为中文 */
    public static String statusText(Integer status) {
        return status != null && status == 1 ? "启用" : "停用";
    }

    /**
     * 组装导入结果：**有失败行**才生成错误 Excel（否则两字段为 {@code null}）。
     *
     * @param errors           全部失败明细（{@code failCount} 取其条数）
     * @param createdDeptNames 本次自动创建的部门名（部门导入传 {@code null} / 空列表）
     */
    public static ImportResultVO buildResult(int total, int success, int skip,
                                             List<ImportErrorVO> errors, List<XlsxRow> failedRows,
                                             List<String> templateHead, String errorFilePrefix,
                                             List<String> createdDeptNames) {
        boolean hasFailure = !errors.isEmpty();
        return ImportResultVO.builder()
                .total(total)
                .successCount(success)
                .skipCount(skip)
                .failCount(errors.size())
                .createdDeptNames(createdDeptNames == null ? List.of() : createdDeptNames)
                .errors(limitErrors(errors))
                .errorFileBase64(hasFailure
                        ? buildErrorFileBase64(templateHead, failedRows, errors) : null)
                .errorFileName(hasFailure ? errorFileName(errorFilePrefix) : null)
                .build();
    }
}
