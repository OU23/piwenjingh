package com.example.toolbox.tool.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.common.result.R;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.tool.dto.ToolBatchDTO;
import com.example.toolbox.tool.service.ToolPurgeService;
import com.example.toolbox.tool.service.ToolTrashService;
import com.example.toolbox.tool.vo.ToolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 删除、回收站与彻底删除（契约 §9.6 `/api/tools`）。
 *
 * <p>六个接口**全部**需要 `tool:delete`（由注解拦截器统一判权：未登录 401、无权限 403）。
 * 与 {@code ToolController} 分文件的原因：本组是"破坏性操作"，集中放置便于审查与加白名单核对。
 *
 * <p><b>路由说明</b>：`/api/tools/deleted` 与 {@code ToolController} 的 `/api/tools/{id}` 同为两段路径，
 * 依赖 Spring PathPattern 的"字面量优先于变量"规则命中本类；
 * 且本类的 `{id}` 均写成 `{id:\\d+}`，使 `deleted` 不可能被当成工具 ID 解析（已由用例钉住该路由）。
 */
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolTrashController {

    private final ToolTrashService toolTrashService;
    private final ToolPurgeService toolPurgeService;

    /** 软删除工具（进回收站，可恢复） */
    @DeleteMapping("/{id:\\d+}")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<Void> delete(@PathVariable String id) {
        toolTrashService.softDelete(IdUtils.parse(id, "工具ID"));
        return R.ok();
    }

    /** 批量软删除（对不存在 / 不可见 / 已删除的 ID 忽略，整体仍成功） */
    @PostMapping("/batch-delete")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<Void> batchDelete(@RequestBody ToolBatchDTO dto) {
        toolTrashService.batchDelete(dto);
        return R.ok();
    }

    /** 已删除工具列表（可见范围内；全局管理员可见全部部门） */
    @GetMapping("/deleted")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<PageResult<ToolVO>> deleted(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "20") int pageSize,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(toolTrashService.deletedPage(pageNum, pageSize, keyword));
    }

    /** 恢复已删除工具（工具与其全部版本一并回到原状态） */
    @PostMapping("/{id:\\d+}/restore")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<Void> restore(@PathVariable String id) {
        toolTrashService.restore(IdUtils.parse(id, "工具ID"));
        return R.ok();
    }

    /** 批量彻底删除（**不可恢复**，连磁盘文件一并删除；仅作用于已软删工具） */
    @PostMapping("/batch-purge")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<Void> purge(@RequestBody ToolBatchDTO dto) {
        toolPurgeService.purge(dto);
        return R.ok();
    }

    /** 清空回收站（彻底删除当前用户可见的全部已删除工具；空回收站同样成功） */
    @DeleteMapping("/deleted")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<Void> purgeAll() {
        toolPurgeService.purgeAll();
        return R.ok();
    }
}
