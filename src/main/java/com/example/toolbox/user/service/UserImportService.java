package com.example.toolbox.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.excel.ExcelConstants;
import com.example.toolbox.excel.ImportErrorVO;
import com.example.toolbox.excel.ImportResultVO;
import com.example.toolbox.excel.ImportSupport;
import com.example.toolbox.excel.XlsxReader;
import com.example.toolbox.excel.XlsxRow;
import com.example.toolbox.excel.XlsxWriter;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.mapper.UserMapper;
import com.example.toolbox.user.vo.UserVO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户 Excel 导入导出（M-19 / 契约 §六 · §6.2）。
 *
 * <p><b>红线</b>：落库**复用既有 {@link UserService#create}**；字段规则**复用 {@link UserSaveDTO}
 * 的校验注解**（经 {@code Validator#validateProperty} 逐字段触发，避免触发 {@code deptId} 必填）；
 * **逐行独立事务**（本类不加 {@code @Transactional}）。
 *
 * <p><b>自动建部门</b>（契约 §2.2）：Excel 里引用的部门不存在 → **自动创建**并计入
 * {@code createdDeptNames}；同一批次内同名只建一次；部门名超 50 → 该行失败且**不建部门**。
 */
@Service
@RequiredArgsConstructor
public class UserImportService {

    private static final List<String> EXPORT_HEAD =
            List.of("账号", "姓名", "部门名称", "状态", "创建时间");

    /** 部门名长度上限（模板列约束，与 DeptSaveDTO 的 @Size 一致） */
    private static final int DEPT_NAME_MAX = 50;

    private final UserService userService;
    private final DeptService deptService;
    private final DeptMapper deptMapper;
    private final UserMapper userMapper;
    private final Validator validator;

    /** 导入模板（含 1 行示例；表头与 {@link ExcelConstants#USER_HEAD} 一致） */
    public byte[] template() {
        return XlsxWriter.toBytes(ExcelConstants.USER_SHEET,
                XlsxWriter.headOf(ExcelConstants.USER_HEAD),
                List.of(ExcelConstants.USER_SAMPLE_ROW));
    }

    /** 导出（{@code keyword} / {@code deptId} 与列表同义；**不含任何密码字段**，状态为中文） */
    public byte[] export(String keyword, String deptId) {
        List<List<String>> rows = userService.exportList(keyword, deptId).stream()
                .map(this::toExportRow)
                .toList();
        return XlsxWriter.toBytes(ExcelConstants.USER_SHEET,
                XlsxWriter.headOf(EXPORT_HEAD), rows);
    }

    /** 导入（逐行独立事务：已存在 → 跳过；校验不过 → 失败；部门不存在 → 自动创建） */
    public ImportResultVO importUsers(MultipartFile file) {
        ImportSupport.validateFile(file);

        List<XlsxRow> rows = readRows(file);
        if (rows.isEmpty()) {
            throw new BusinessException(Code.PARAM_INVALID, "文件中没有可导入的数据行");
        }

        // 预加载：部门名 → id、已存在账号（各一次查询，避免 N+1）
        Map<String, Long> deptByName = new HashMap<>();
        for (DeptDO dept : deptMapper.selectList(null)) {
            deptByName.put(dept.getName(), dept.getId());
        }
        Set<String> existingUsernames = new HashSet<>(userMapper.selectList(null).stream()
                .map(UserDO::getUsername)
                .toList());

        List<ImportErrorVO> errors = new ArrayList<>();
        List<XlsxRow> failedRows = new ArrayList<>();
        List<String> createdDeptNames = new ArrayList<>();
        int success = 0;
        int skip = 0;

        for (XlsxRow row : rows) {
            String username = row.cell(0);
            String realName = row.cell(1);
            String deptName = row.cell(2);
            String password = row.cell(3);

            // ① 部门名（模板列约束：非空 + ≤50）—— 不通过则该行失败，且**不创建部门**
            if (deptName.isBlank()) {
                errors.add(new ImportErrorVO(row.rowNum(), "部门名称不能为空"));
                failedRows.add(row);
                continue;
            }
            if (deptName.length() > DEPT_NAME_MAX) {
                errors.add(new ImportErrorVO(row.rowNum(),
                        "部门名称不能超过 " + DEPT_NAME_MAX + " 字符"));
                failedRows.add(row);
                continue;
            }

            // ② 账号 / 姓名 / 密码：复用 UserSaveDTO 的注解规则（逐字段，避开 deptId 必填）
            UserSaveDTO dto = new UserSaveDTO();
            dto.setUsername(username);
            dto.setRealName(realName);
            dto.setPassword(password);
            String invalid = validateUserFields(dto);
            if (invalid != null) {
                errors.add(new ImportErrorVO(row.rowNum(), invalid));
                failedRows.add(row);
                continue;
            }

            // ③ 已存在 → 跳过（不算失败、不进错误 Excel）
            if (existingUsernames.contains(username)) {
                skip++;
                continue;
            }

            // ④ 落库：解析 / 自动创建部门 → 建号（既有 service，独立事务）
            try {
                Long deptId = resolveOrCreateDept(deptName, deptByName, createdDeptNames);
                dto.setDeptId(IdUtils.toStr(deptId));
                userService.create(dto);
                existingUsernames.add(username);
                success++;
            } catch (DuplicateKeyException e) {
                skip++; // 并发抢先插入 → 视为跳过（幂等）
            } catch (BusinessException e) {
                errors.add(new ImportErrorVO(row.rowNum(), e.getMessage()));
                failedRows.add(row);
            }
        }

        return ImportSupport.buildResult(rows.size(), success, skip, errors, failedRows,
                ExcelConstants.USER_HEAD, ExcelConstants.USER_ERROR_FILE_PREFIX, createdDeptNames);
    }

    // ---------------- 内部 ----------------

    /** 账号 / 姓名 / 密码三字段的规则校验（逐字段，避免触发 deptId 的必填约束） */
    private String validateUserFields(UserSaveDTO dto) {
        for (String field : List.of("username", "realName", "password")) {
            Set<ConstraintViolation<UserSaveDTO>> violations = validator.validateProperty(dto, field);
            if (!violations.isEmpty()) {
                return violations.iterator().next().getMessage();
            }
        }
        return null;
    }

    /** 部门不存在则**自动创建**（同批次同名只建一次；复用既有 DeptService 校验） */
    private Long resolveOrCreateDept(String deptName, Map<String, Long> deptByName,
                                     List<String> createdDeptNames) {
        Long existing = deptByName.get(deptName);
        if (existing != null) {
            return existing;
        }
        DeptSaveDTO dept = new DeptSaveDTO();
        dept.setName(deptName);
        Long id = Long.valueOf(deptService.create(dept));
        deptByName.put(deptName, id);
        createdDeptNames.add(deptName);
        return id;
    }

    private List<XlsxRow> readRows(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return XlsxReader.read(in, 1, ExcelConstants.USER_HEAD.size());
        } catch (IOException e) {
            throw new BusinessException(Code.PARAM_INVALID, "文件读取失败，请重试");
        }
    }

    /** 导出行：**不含任何密码字段** */
    private List<String> toExportRow(UserVO vo) {
        return List.of(
                ImportSupport.nz(vo.getUsername()),
                ImportSupport.nz(vo.getRealName()),
                ImportSupport.nz(vo.getDeptName()),
                ImportSupport.statusText(vo.getStatus()),
                ImportSupport.nz(vo.getCreateTime()));
    }

    /** 供测试断言"导出/导入未触及密码列"时使用（不经 Excel，仅列定义） */
    static List<String> exportHead() {
        return EXPORT_HEAD;
    }

    /** 已存在账号判定（与导入同口径，供需要时复用） */
    boolean usernameExists(String username) {
        return userMapper.selectCount(
                Wrappers.<UserDO>lambdaQuery().eq(UserDO::getUsername, username)) > 0;
    }
}
