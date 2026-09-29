package com.example.toolbox.dept.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.R;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.dto.DeptStatusDTO;
import com.example.toolbox.dept.service.DeptImportService;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.dept.vo.DeptVO;
import com.example.toolbox.excel.ExcelConstants;
import com.example.toolbox.excel.ExcelResponse;
import com.example.toolbox.excel.ImportResultVO;
import com.example.toolbox.excel.ImportSupport;
import com.example.toolbox.security.RequiresPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 部门（契约 §四 /api/depts）。路由 + 参数接收 + 权限入口 + 统一响应，业务在 DeptService。
 */
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;
    private final DeptImportService deptImportService;

    /** 列表：登录即可（无权限点要求） */
    @GetMapping
    public R<List<DeptVO>> list(@RequestParam(required = false) Integer status) {
        return R.ok(deptService.list(status));
    }

    @PostMapping
    @RequiresPermission(PermissionCode.DEPT_MANAGE)
    public R<String> create(@RequestBody @Valid DeptSaveDTO dto) {
        return R.ok(deptService.create(dto));
    }

    @PutMapping("/{id}")
    @RequiresPermission(PermissionCode.DEPT_MANAGE)
    public R<Void> update(@PathVariable String id, @RequestBody @Valid DeptSaveDTO dto) {
        deptService.update(IdUtils.parse(id, "部门ID"), dto);
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @RequiresPermission(PermissionCode.DEPT_MANAGE)
    public R<Void> updateStatus(@PathVariable String id, @RequestBody @Valid DeptStatusDTO dto) {
        deptService.updateStatus(IdUtils.parse(id, "部门ID"), dto);
        return R.ok();
    }

    // ---------------- Excel 导入导出（M-19 / 契约 §四 · §6.2） ----------------

    /** 下载部门导入模板（Excel 文件流，不使用统一响应体） */
    @GetMapping("/import-template")
    @RequiresPermission(PermissionCode.DEPT_MANAGE)
    public void importTemplate(HttpServletResponse response) throws IOException {
        ExcelResponse.write(response, deptImportService.template(), ExcelConstants.DEPT_TEMPLATE_FILE);
    }

    /** 导入部门（multipart 字段名固定 `file`；**逐行独立事务**，只提交成功的行） */
    @PostMapping("/import")
    @RequiresPermission(PermissionCode.DEPT_MANAGE)
    public R<ImportResultVO> importDepts(@RequestParam("file") MultipartFile file) {
        return R.ok(deptImportService.importDepts(file));
    }

    /** 导出部门（`status` 不传 = 全量；走鉴权通道，不做静态直链） */
    @GetMapping("/export")
    @RequiresPermission(PermissionCode.DEPT_MANAGE)
    public void export(@RequestParam(required = false) Integer status,
                       HttpServletResponse response) throws IOException {
        ExcelResponse.write(response, deptImportService.export(status),
                ImportSupport.timestampedFileName(ExcelConstants.DEPT_EXPORT_PREFIX));
    }
}
