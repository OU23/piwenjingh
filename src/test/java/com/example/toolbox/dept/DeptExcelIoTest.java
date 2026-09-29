package com.example.toolbox.dept;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.service.DeptImportService;
import com.example.toolbox.excel.ExcelConstants;
import com.example.toolbox.excel.ImportErrorVO;
import com.example.toolbox.excel.ImportResultVO;
import com.example.toolbox.excel.XlsxWriter;
import com.example.toolbox.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试范围：部门 Excel 导入导出（M-19 / 契约 §四 · §6.2）
 * 测试类型：集成（H2 + 真实 Spring 上下文）
 * 测试目的：锁死「跳过 ≠ 失败」「只提交成功的行」「模板与导出列口径」三条核心
 * 防回归问题：跳过行被写进错误 Excel、失败行被误入库、导出列顺序漂移、状态未转中文、
 *             全空行计入 total、非 xlsx / 超 10MB 未整体失败
 * 关键不变量：
 *   - 已存在部门 → skipCount 且**不进** errors / 错误 Excel
 *   - 校验不通过 → failCount + 错误 Excel（rowNum = **Excel 实际行号**）
 *   - 失败行**不入库**、成功行**照常入库**（不做整体回滚）
 *   - 导出列顺序 = 部门名称 / 备注 / 状态 / 用户数 / 创建时间，状态为中文
 * 边界说明：上传文件由被测的 XlsxWriter 生成（与模板同一口径）；无失败行时错误文件两字段为 null
 * 依赖与限制：不覆盖前端与真实浏览器
 */
class DeptExcelIoTest extends IntegrationTestBase {

    @Autowired
    private DeptImportService deptImportService;

    private MockMultipartFile file(List<List<String>> dataRows) {
        byte[] bytes = XlsxWriter.toBytes(ExcelConstants.DEPT_SHEET,
                XlsxWriter.headOf(ExcelConstants.DEPT_HEAD), dataRows);
        return new MockMultipartFile("file", "depts.xlsx", ExcelConstants.XLSX_CONTENT_TYPE, bytes);
    }

    /** 读回 xlsx（`headRowNumber(0)`：表头也当数据读，便于断言列定义） */
    private List<List<String>> readBack(byte[] bytes, int minColumns) {
        List<Map<Integer, String>> raw = EasyExcel.read(new ByteArrayInputStream(bytes))
                .sheet(0).headRowNumber(0).doReadSync();
        List<List<String>> rows = new ArrayList<>();
        for (Map<Integer, String> row : raw) {
            // 列数自适应：取"最小期望列数"与"实际最大列号 + 1"的较大者 ——
            // 这样多写了不该有的列也会被读出来，避免固定列数导致断言假绿
            int columns = Math.max(minColumns, row.isEmpty() ? 0 : Collections.max(row.keySet()) + 1);
            List<String> cells = new ArrayList<>(columns);
            for (int i = 0; i < columns; i++) {
                String value = row.get(i);
                // 空单元格读回为 null，统一归一为空串便于断言
                cells.add(value == null ? "" : value);
            }
            rows.add(cells);
        }
        return rows;
    }

    private DeptDO newDept(String name) {
        DeptDO d = new DeptDO();
        d.setName(name);
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        return d;
    }

    private DeptDO findDept(String name) {
        return deptMapper.selectOne(Wrappers.<DeptDO>lambdaQuery().eq(DeptDO::getName, name));
    }

    // ---------------- 模板 ----------------

    @Test
    void templateHasExpectedHeadAndSample() {
        List<List<String>> rows = readBack(deptImportService.template(), 2);

        assertEquals(List.of("部门名称", "备注"), rows.get(0), "模板表头须与契约一致");
        assertEquals(2, rows.size(), "模板应含 1 行示例");
        assertTrue(rows.get(1).get(1).contains("示例"), "示例行须提示正式导入前删除");
    }

    // ---------------- 导入 ----------------

    @Test
    void importCreatesNewSkipsExistingAndRejectsInvalid() {
        deptMapper.insert(newDept("研发部")); // 预置已存在

        MockMultipartFile upload = file(List.of(
                List.of("新部门A", "备注A"),   // 第 2 行 → 成功
                List.of("研发部", "重复"),     // 第 3 行 → 跳过
                List.of("", "空名"),           // 第 4 行 → 失败
                List.of("超长".repeat(26), ""))); // 第 5 行 → 失败（52 > 50）

        ImportResultVO result = deptImportService.importDepts(upload);

        assertEquals(4, result.getTotal());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getSkipCount());
        assertEquals(2, result.getFailCount());

        List<Integer> errorRows = result.getErrors().stream().map(ImportErrorVO::rowNum).toList();
        assertEquals(List.of(4, 5), errorRows, "rowNum 应为 Excel 实际行号");
        assertFalse(errorRows.contains(3), "跳过行不得进错误明细");

        assertNotNull(result.getErrorFileBase64(), "有失败行须给出错误 Excel");
        assertTrue(result.getErrorFileName().startsWith(ExcelConstants.DEPT_ERROR_FILE_PREFIX));

        // 只提交成功的行
        assertNotNull(findDept("新部门A"), "成功行应已入库");
        assertNotNull(findDept("研发部"), "已存在部门保持原样");
        assertNull(findDept(""), "失败行不得入库");
    }

    @Test
    void errorExcelHasOriginalColumnsPlusReason() {
        MockMultipartFile upload = file(List.of(List.of("", "备注")));

        ImportResultVO result = deptImportService.importDepts(upload);
        List<List<String>> rows = readBack(
                java.util.Base64.getDecoder().decode(result.getErrorFileBase64()), 3);

        assertEquals(List.of("部门名称", "备注", "错误原因"), rows.get(0),
                "错误 Excel 表头 = 原模板列 + 末列错误原因");
        assertEquals("", rows.get(1).get(0), "应保留用户填写的原文");
        assertFalse(rows.get(1).get(2).isBlank(), "错误原因不得为空");
    }

    @Test
    void allSkippedProducesNoErrorFile() {
        deptMapper.insert(newDept("研发部"));

        ImportResultVO result = deptImportService.importDepts(file(List.of(List.of("研发部", ""))));

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getSkipCount());
        assertEquals(0, result.getFailCount());
        assertNull(result.getErrorFileBase64(), "无失败行时不得给出错误 Excel");
        assertNull(result.getErrorFileName());
    }

    @Test
    void blankRowsAreNotCounted() {
        ImportResultVO result = deptImportService.importDepts(file(List.of(
                List.of("", ""),        // 全空行
                List.of("部门X", ""))));

        assertEquals(1, result.getTotal(), "全空行不计入 total");
        assertEquals(1, result.getSuccessCount());
    }

    @Test
    void headerOnlyFileIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deptImportService.importDepts(file(List.of())));
        assertEquals(Code.PARAM_INVALID, ex.getCode());
        assertEquals(1003, ex.getCode().getCode());
    }

    @Test
    void nonXlsxFileIsRejected() {
        MockMultipartFile csv = new MockMultipartFile("file", "depts.csv", "text/csv", "a,b".getBytes());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deptImportService.importDepts(csv));
        assertEquals(Code.PARAM_INVALID, ex.getCode());
    }

    @Test
    void oversizedFileIsRejected() {
        byte[] huge = new byte[(int) ExcelConstants.MAX_IMPORT_BYTES + 1];
        MockMultipartFile big = new MockMultipartFile("file", "big.xlsx",
                ExcelConstants.XLSX_CONTENT_TYPE, huge);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deptImportService.importDepts(big));
        assertEquals(Code.PARAM_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains("10MB"));
    }

    // ---------------- 导出 ----------------

    @Test
    void exportColumnsOrderAndChineseStatus() {
        deptMapper.insert(newDept("研发部"));

        List<List<String>> rows = readBack(deptImportService.export(null), 5);

        assertEquals(List.of("部门名称", "备注", "状态", "用户数", "创建时间"), rows.get(0));
        assertEquals("研发部", rows.get(1).get(0));
        assertEquals("启用", rows.get(1).get(2), "状态须导出为中文");
        assertFalse(rows.get(1).get(4).isBlank(), "创建时间不应为空");
    }

    @Test
    void exportWithoutStatusReturnsAll() {
        deptMapper.insert(newDept("启用部"));
        DeptDO disabled = newDept("停用部");
        disabled.setStatus(StatusValue.DISABLED);
        deptMapper.insert(disabled);

        assertEquals(2, readBack(deptImportService.export(null), 5).size() - 1,
                "不传 status = 全量");
        assertEquals(1, readBack(deptImportService.export(StatusValue.ENABLED), 5).size() - 1,
                "按状态筛选应只返回匹配项");
    }
}
