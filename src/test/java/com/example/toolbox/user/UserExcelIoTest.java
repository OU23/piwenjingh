package com.example.toolbox.user;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.excel.ExcelConstants;
import com.example.toolbox.excel.ImportResultVO;
import com.example.toolbox.excel.XlsxWriter;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.service.UserImportService;
import com.example.toolbox.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试范围：用户 Excel 导入导出（M-19 / 契约 §六 · §6.2）
 * 测试类型：集成（H2 + 真实 Spring 上下文）
 * 测试目的：锁死「自动建部门」「同批次同名只建一次」「跳过 ≠ 失败」「导出不含密码」四条核心
 * 防回归问题：部门被重复创建、超长部门名仍建部门、已存在账号被当失败、同文件重复账号报错、
 *             导出混入密码列、导出与列表筛选口径不一致
 * 关键不变量：
 *   - 引用的部门不存在 → **自动创建**并计入 createdDeptNames；同批次同名**只建一次**
 *   - 部门名超 50 → 该行失败且**不创建部门**
 *   - 已存在账号 → skipCount（不进错误 Excel）；同文件内重复账号 → 首行成功、次行跳过
 *   - 字段规则复用 UserSaveDTO 注解（账号格式 / 姓名 / 密码长度）
 *   - 导出列 = 账号 / 姓名 / 部门名称 / 状态 / 创建时间，**不含任何密码字段**
 * 边界说明：上传文件由被测的 XlsxWriter 生成（与模板同一口径）
 * 依赖与限制：不覆盖前端与真实浏览器
 */
class UserExcelIoTest extends IntegrationTestBase {

    @Autowired
    private UserImportService userImportService;
    @Autowired
    private UserService userService;

    private Long deptId;

    @BeforeEach
    void setUpData() {
        DeptDO dept = new DeptDO();
        dept.setName("现有部门");
        dept.setStatus(StatusValue.ENABLED);
        dept.setCreateTime(LocalDateTime.now());
        deptMapper.insert(dept);
        deptId = dept.getId();
    }

    private MockMultipartFile file(List<List<String>> dataRows) {
        byte[] bytes = XlsxWriter.toBytes(ExcelConstants.USER_SHEET,
                XlsxWriter.headOf(ExcelConstants.USER_HEAD), dataRows);
        return new MockMultipartFile("file", "users.xlsx", ExcelConstants.XLSX_CONTENT_TYPE, bytes);
    }

    private List<List<String>> readBack(byte[] bytes, int minColumns) {
        List<Map<Integer, String>> raw = EasyExcel.read(new ByteArrayInputStream(bytes))
                .sheet(0).headRowNumber(0).doReadSync();
        List<List<String>> rows = new ArrayList<>();
        for (Map<Integer, String> row : raw) {
            // 列数自适应：取"最小期望列数"与"实际最大列号 + 1"的较大者
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

    private void createUser(String username, String realName, String password) {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername(username);
        dto.setRealName(realName);
        dto.setPassword(password);
        dto.setDeptId(IdUtils.toStr(deptId));
        userService.create(dto);
    }

    private UserDO findUser(String username) {
        return userMapper.selectOne(Wrappers.<UserDO>lambdaQuery().eq(UserDO::getUsername, username));
    }

    private DeptDO findDept(String name) {
        return deptMapper.selectOne(Wrappers.<DeptDO>lambdaQuery().eq(DeptDO::getName, name));
    }

    // ---------------- 模板 ----------------

    @Test
    void templateHeadMatchesContract() {
        List<List<String>> rows = readBack(userImportService.template(), 4);

        assertEquals(List.of("账号", "姓名", "部门名称", "初始密码"), rows.get(0));
        assertEquals(2, rows.size(), "模板应含 1 行示例");
    }

    // ---------------- 导入：自动建部门 ----------------

    @Test
    void importCreatesUserAndAutoCreatesMissingDept() {
        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("zhangsan", "张三", "新部门", "123456"))));

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSuccessCount());
        assertEquals(List.of("新部门"), result.getCreatedDeptNames(), "应报告自动创建的部门");
        assertNotNull(findDept("新部门"), "部门应被自动创建");
        assertNotNull(findUser("zhangsan"), "用户应入库");
    }

    @Test
    void sameNewDeptInOneBatchIsCreatedOnce() {
        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("u001", "甲", "共享部", "123456"),
                List.of("u002", "乙", "共享部", "123456"))));

        assertEquals(2, result.getSuccessCount());
        assertEquals(List.of("共享部"), result.getCreatedDeptNames(), "同批次同名部门只建一次");
        assertEquals(1, deptMapper.selectCount(Wrappers.<DeptDO>lambdaQuery().eq(DeptDO::getName, "共享部")));
    }

    @Test
    void existingDeptIsReusedNotRecreated() {
        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("u001", "甲", "现有部门", "123456"))));

        assertEquals(1, result.getSuccessCount());
        assertTrue(result.getCreatedDeptNames().isEmpty(), "已存在部门不得计入 createdDeptNames");
        assertEquals(1, deptMapper.selectCount(Wrappers.<DeptDO>lambdaQuery().eq(DeptDO::getName, "现有部门")));
    }

    @Test
    void overlongDeptNameFailsWithoutCreatingDept() {
        String tooLong = "长".repeat(51);

        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("u001", "甲", tooLong, "123456"))));

        assertEquals(1, result.getFailCount());
        assertTrue(result.getCreatedDeptNames().isEmpty());
        assertNull(findDept(tooLong), "超长部门名不得创建");
        assertNull(findUser("u001"), "该行不得入库");
        assertNotNull(result.getErrorFileBase64());
    }

    // ---------------- 导入：跳过 ≠ 失败 ----------------

    @Test
    void existingAccountIsSkippedNotFailed() {
        createUser("zhangsan", "张三", "123456");

        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("zhangsan", "张三改名", "现有部门", "654321"))));

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getSkipCount());
        assertEquals(0, result.getFailCount());
        assertNull(result.getErrorFileBase64(), "全跳过不得给出错误 Excel");
        assertEquals("张三", findUser("zhangsan").getRealName(), "已存在账号不得被更新");
    }

    @Test
    void duplicateAccountWithinSameFileFirstRowWins() {
        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("dup001", "甲", "现有部门", "123456"),
                List.of("dup001", "乙", "现有部门", "123456"))));

        assertEquals(1, result.getSuccessCount(), "首行应成功建号");
        assertEquals(1, result.getSkipCount(), "次行按已存在跳过");
        assertEquals(0, result.getFailCount());
        assertEquals("甲", findUser("dup001").getRealName());
    }

    // ---------------- 导入：字段规则复用（C11） ----------------

    @Test
    void invalidAccountFormatFailsWithReusedRule() {
        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("a", "甲", "现有部门", "123456"))));

        assertEquals(1, result.getFailCount());
        assertTrue(result.getErrors().get(0).message().contains("账号"),
                "应复用 UserSaveDTO 的账号规则消息，实际：" + result.getErrors().get(0).message());
    }

    @Test
    void tooShortPasswordFailsWithReusedRule() {
        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("u001", "甲", "现有部门", "123"))));

        assertEquals(1, result.getFailCount());
        assertTrue(result.getErrors().get(0).message().contains("密码"),
                "应复用 UserSaveDTO 的密码规则消息，实际：" + result.getErrors().get(0).message());
    }

    @Test
    void blankDeptNameFailsWithoutCreatingDept() {
        ImportResultVO result = userImportService.importUsers(file(List.of(
                List.of("u001", "甲", "", "123456"))));

        assertEquals(1, result.getFailCount());
        assertTrue(result.getCreatedDeptNames().isEmpty());
    }

    // ---------------- 导出（★ 不含密码） ----------------

    @Test
    void exportHeadHasNoPasswordColumn() {
        List<List<String>> rows = readBack(userImportService.export(null, null), 5);

        assertEquals(List.of("账号", "姓名", "部门名称", "状态", "创建时间"), rows.get(0));
        String head = String.join("|", rows.get(0));
        assertFalse(head.contains("密码"), "导出表头不得含任何密码列");
    }

    @Test
    void exportRowsCarryChineseStatusAndNoSecret() {
        createUser("zhangsan", "张三", "123456");

        List<List<String>> rows = readBack(userImportService.export(null, null), 5);
        List<String> row = rows.get(1);

        assertEquals("zhangsan", row.get(0));
        assertEquals("张三", row.get(1));
        assertEquals("现有部门", row.get(2));
        assertEquals("启用", row.get(3), "状态应为中文");
        assertFalse(String.join("|", row).contains("123456"), "导出不得出现密码值");
    }

    @Test
    void exportRespectsKeywordFilter() {
        createUser("zhangsan", "张三", "123456");
        createUser("lisi", "李四", "123456");

        List<List<String>> all = readBack(userImportService.export(null, null), 5);
        List<List<String>> filtered = readBack(userImportService.export("zhang", null), 5);

        assertEquals(2, all.size() - 1);
        assertEquals(1, filtered.size() - 1, "导出应与列表同筛选口径");
        assertEquals("zhangsan", filtered.get(1).get(0));
    }
}
