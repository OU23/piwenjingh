package com.example.toolbox.user.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.common.result.R;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.excel.ExcelConstants;
import com.example.toolbox.excel.ExcelResponse;
import com.example.toolbox.excel.ImportResultVO;
import com.example.toolbox.excel.ImportSupport;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.security.SessionService;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.user.dto.UserBatchPermissionDTO;
import com.example.toolbox.user.dto.UserBatchStatusDTO;
import com.example.toolbox.user.dto.UserPermissionDTO;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.dto.UserStatusDTO;
import com.example.toolbox.user.dto.UserUpdateDTO;
import com.example.toolbox.user.service.UserBatchService;
import com.example.toolbox.user.service.UserImportService;
import com.example.toolbox.user.service.UserService;
import com.example.toolbox.tool.dto.UserToolGrantDTO;
import com.example.toolbox.tool.service.ToolGrantNotifier;
import com.example.toolbox.tool.service.ToolGrantService;
import com.example.toolbox.tool.vo.ToolBriefVO;
import com.example.toolbox.user.vo.UserVO;
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
 * 用户（契约 §六 /api/users）。路由 + 参数 + 权限入口 + 统一响应，业务在 UserService。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserBatchService userBatchService;
    private final ToolGrantService toolGrantService;
    /** v1.17：定向授权变更通知（只推不关连接） */
    private final ToolGrantNotifier toolGrantNotifier;
    private final UserImportService userImportService;
    /** 会话终结（M-21）：权限变更后立即让旧会话失效 */
    private final SessionService sessionService;

    @GetMapping
    @RequiresPermission(any = {PermissionCode.USER_MANAGE, PermissionCode.USER_GRANT})
    public R<PageResult<UserVO>> list(@RequestParam(defaultValue = "1") int pageNum,
                                      @RequestParam(defaultValue = "20") int pageSize,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String deptId) {
        return R.ok(userService.page(pageNum, pageSize, keyword, deptId));
    }

    @GetMapping("/{id}")
    @RequiresPermission(PermissionCode.USER_MANAGE)
    public R<UserVO> detail(@PathVariable String id) {
        return R.ok(userService.detail(IdUtils.parse(id, "用户ID")));
    }

    @PostMapping
    @RequiresPermission(PermissionCode.USER_MANAGE)
    public R<String> create(@RequestBody @Valid UserSaveDTO dto) {
        return R.ok(userService.create(dto));
    }

    /** 编辑：仅姓名与归属部门可改 */
    @PutMapping("/{id}")
    @RequiresPermission(PermissionCode.USER_MANAGE)
    public R<Void> update(@PathVariable String id, @RequestBody @Valid UserUpdateDTO dto) {
        userService.update(IdUtils.parse(id, "用户ID"), dto);
        return R.ok();
    }

    /** 启用 / 停用（契约 §六，v1.4） */
    @PutMapping("/{id}/status")
    @RequiresPermission(PermissionCode.USER_MANAGE)
    public R<Void> updateStatus(@PathVariable String id, @RequestBody @Valid UserStatusDTO dto) {
        userService.updateStatus(IdUtils.parse(id, "用户ID"), dto);
        return R.ok();
    }

    @GetMapping("/{id}/permissions")
    @RequiresPermission(PermissionCode.USER_GRANT)
    public R<List<String>> getPermissions(@PathVariable String id) {
        return R.ok(userService.getPermissions(IdUtils.parse(id, "用户ID")));
    }

    /**
     * 保存权限点（契约 §六）。
     *
     * <p>**M-21（契约 v1.12）**：权限**实际发生变化**时，立即**终结该账号旧会话**
     * （旧令牌下一次请求即 `401`）+ 经 WS 推送 `PERMISSION_CHANGED`（关闭码 `4004`）。
     * <p>**豁免**（不推送、不下线）：目标账号是**全局管理员**（权限点对其无意义）
     * 或**当前操作人**（自己改自己不必自我打断）。
     */
    @PutMapping("/{id}/permissions")
    @RequiresPermission(PermissionCode.USER_GRANT)
    public R<Void> savePermissions(@PathVariable String id, @RequestBody @Valid UserPermissionDTO dto) {
        Long targetId = IdUtils.parse(id, "用户ID");
        boolean changed = userService.updatePermissions(targetId, dto.getPermissions());
        Long operatorId = UserContext.get().getUserId();
        if (changed && !targetId.equals(operatorId) && !userService.isSystemAdmin(targetId)) {
            sessionService.forceLogoutByPermissionChange(targetId);
        }
        return R.ok();
    }

    /**
     * 批量启用 / 停用（契约 v1.16 §六）。
     *
     * <p>逐账号复用 {@link UserService#updateStatus} 的校验与联动（停用即踢会话 / 关 WS）；
     * 忽略不存在 ID；**目标含系统初始化管理员且为停用 → `1005` 整体拒绝**（前置整体校验）；
     * **逐账号独立事务、不整体回滚**（事务边界在 {@code UserBatchService} 的每账号调用）。
     */
    @PostMapping("/batch-status")
    @RequiresPermission(PermissionCode.USER_MANAGE)
    public R<Void> batchUpdateStatus(@RequestBody @Valid UserBatchStatusDTO dto) {
        userBatchService.batchUpdateStatus(dto);
        return R.ok();
    }

    /**
     * 批量分配权限（契约 v1.16 §六，**覆盖式**）。
     *
     * <p>入参 `permissions` 为**最终权限全集**；逐账号复用 {@link UserService#updatePermissions}
     * （覆盖保存 + 是否变化）；变化时终结旧会话并推 `PERMISSION_CHANGED`（豁免：当前操作人）；
     * 目标含系统初始化管理员 → `1005` 整体拒绝（前置整体校验）。
     */
    @PostMapping("/batch-permissions")
    @RequiresPermission(PermissionCode.USER_GRANT)
    public R<Void> batchSavePermissions(@RequestBody @Valid UserBatchPermissionDTO dto) {
        userBatchService.batchSavePermissions(dto);
        return R.ok();
    }

    /** 查询某用户被授权的工具（用户侧，契约 §6.1） */
    @GetMapping("/{id}/tool-grants")
    @RequiresPermission(PermissionCode.USER_GRANT)
    public R<List<ToolBriefVO>> listToolGrants(@PathVariable String id) {
        return R.ok(toolGrantService.listToolsForUser(IdUtils.parse(id, "用户ID")));
    }

    /** 保存某用户的工具授权（用户侧，契约 §6.1；全量覆盖） */
    @PutMapping("/{id}/tool-grants")
    @RequiresPermission(PermissionCode.USER_GRANT)
    public R<Void> saveToolGrants(@PathVariable String id, @RequestBody UserToolGrantDTO dto) {
        Long targetId = IdUtils.parse(id, "用户ID");
        Long operatorId = UserContext.get().getUserId();
        // v1.17：保存后（事务外）通知该用户刷新工具列表（只推不关连接；豁免操作人；异常吞掉）
        List<Long> affected = toolGrantService.saveToolsForUser(targetId, dto.getToolIds(), operatorId);
        toolGrantNotifier.notifyChanged(affected, operatorId);
        return R.ok();
    }

    // ---------------- Excel 导入导出（M-19 / 契约 §六 · §6.2） ----------------

    /** 下载用户导入模板（Excel 文件流，不使用统一响应体） */
    @GetMapping("/import-template")
    @RequiresPermission(PermissionCode.USER_MANAGE)
    public void importTemplate(HttpServletResponse response) throws IOException {
        ExcelResponse.write(response, userImportService.template(), ExcelConstants.USER_TEMPLATE_FILE);
    }

    /** 导入用户（multipart 字段名固定 `file`；**逐行独立事务**；引用的部门不存在则自动创建） */
    @PostMapping("/import")
    @RequiresPermission(PermissionCode.USER_MANAGE)
    public R<ImportResultVO> importUsers(@RequestParam("file") MultipartFile file) {
        return R.ok(userImportService.importUsers(file));
    }

    /** 导出用户（`keyword` / `deptId` 与列表同义；**不含任何密码字段**） */
    @GetMapping("/export")
    @RequiresPermission(PermissionCode.USER_MANAGE)
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String deptId,
                       HttpServletResponse response) throws IOException {
        ExcelResponse.write(response, userImportService.export(keyword, deptId),
                ImportSupport.timestampedFileName(ExcelConstants.USER_EXPORT_PREFIX));
    }
}
