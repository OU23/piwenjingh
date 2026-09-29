package com.example.toolbox.dept.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.dept.vo.DeptVO;
import com.example.toolbox.excel.ExcelConstants;
import com.example.toolbox.excel.ImportErrorVO;
import com.example.toolbox.excel.ImportResultVO;
import com.example.toolbox.excel.ImportSupport;
import com.example.toolbox.excel.XlsxReader;
import com.example.toolbox.excel.XlsxRow;
import com.example.toolbox.excel.XlsxWriter;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 部门 Excel 导入导出（M-19 / 契约 §四 · §6.2）。
 *
 * <p><b>红线</b>：落库**复用既有 {@link DeptService#create}**（含唯一性校验，不重写一套）；
 * 长度 / 必填等规则**复用 {@link DeptSaveDTO} 的校验注解**（经 {@code Validator} 手动触发）；
 * **逐行独立事务** —— 本类**不加 {@code @Transactional}**，靠既有 service 方法自带事务保证"只提交成功行"。
 */
@Service
@RequiredArgsConstructor
public class DeptImportService {

    private static final List<String> EXPORT_HEAD =
            List.of("部门名称", "备注", "状态", "用户数", "创建时间");

    private final DeptService deptService;
    private final DeptMapper deptMapper;
    private final Validator validator;

    /** 导入模板（含 1 行示例；表头与 {@link ExcelConstants#DEPT_HEAD} 一致） */
    public byte[] template() {
        return XlsxWriter.toBytes(ExcelConstants.DEPT_SHEET,
                XlsxWriter.headOf(ExcelConstants.DEPT_HEAD),
                List.of(ExcelConstants.DEPT_SAMPLE_ROW));
    }

    /** 导出（{@code status} 不传 = 全量；列顺序固定，状态为中文） */
    public byte[] export(Integer status) {
        List<List<String>> rows = deptService.list(status).stream()
                .map(this::toExportRow)
                .toList();
        return XlsxWriter.toBytes(ExcelConstants.DEPT_SHEET,
                XlsxWriter.headOf(EXPORT_HEAD), rows);
    }

    /** 导入（逐行独立事务：已存在 → 跳过；校验不过 → 失败） */
    public ImportResultVO importDepts(MultipartFile file) {
        ImportSupport.validateFile(file);

        List<XlsxRow> rows = readRows(file);
        if (rows.isEmpty()) {
            throw new BusinessException(Code.PARAM_INVALID, "文件中没有可导入的数据行");
        }

        // 预加载全部部门名（一次查询，避免 N+1）
        Set<String> existingNames = new HashSet<>(deptMapper.selectList(null).stream()
                .map(DeptDO::getName)
                .toList());

        List<ImportErrorVO> errors = new ArrayList<>();
        List<XlsxRow> failedRows = new ArrayList<>();
        int success = 0;
        int skip = 0;

        for (XlsxRow row : rows) {
            DeptSaveDTO dto = new DeptSaveDTO();
            dto.setName(row.cell(0));
            dto.setRemark(ImportSupport.blankToNull(row.cell(1)));

            String invalid = ImportSupport.validateDto(validator, dto);
            if (invalid != null) {
                errors.add(new ImportErrorVO(row.rowNum(), invalid));
                failedRows.add(row);
                continue;
            }
            if (existingNames.contains(dto.getName())) {
                skip++; // 已存在 → 跳过（不算失败、不进错误 Excel）
                continue;
            }
            try {
                deptService.create(dto); // 复用既有建部门逻辑 + 独立事务
                existingNames.add(dto.getName());
                success++;
            } catch (DuplicateKeyException e) {
                skip++; // 并发抢先插入 → 视为跳过（幂等）
            } catch (BusinessException e) {
                errors.add(new ImportErrorVO(row.rowNum(), e.getMessage()));
                failedRows.add(row);
            }
        }

        return ImportSupport.buildResult(rows.size(), success, skip, errors, failedRows,
                ExcelConstants.DEPT_HEAD, ExcelConstants.DEPT_ERROR_FILE_PREFIX, null);
    }

    // ---------------- 内部 ----------------

    private List<XlsxRow> readRows(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return XlsxReader.read(in, 1, ExcelConstants.DEPT_HEAD.size());
        } catch (IOException e) {
            throw new BusinessException(Code.PARAM_INVALID, "文件读取失败，请重试");
        }
    }

    private List<String> toExportRow(DeptVO vo) {
        return List.of(
                ImportSupport.nz(vo.getName()),
                ImportSupport.nz(vo.getRemark()),
                ImportSupport.statusText(vo.getStatus()),
                String.valueOf(vo.getUserCount()),
                ImportSupport.nz(vo.getCreateTime()));
    }
}
