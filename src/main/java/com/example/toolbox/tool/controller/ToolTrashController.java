package com.example.toolbox.tool.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.common.result.R;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.tool.dto.ToolBatchDTO;
import com.example.toolbox.tool.service.ToolPurgeService;
import com.example.toolbox.tool.service.ToolTrashService;
import com.example.toolbox.tool.service.ToolVersionDeleteService;
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
 * <p><b>判权（v1.21 / v1.22）</b>：
 * <ul>
 *   <li><b>工具删除 / 恢复、历史版本删除</b> —— 上传人本人 / 双权限管理员 / 全局管理员，
 *       由服务层 {@code requireEditable} 判定，**故不加 `@RequiresPermission`**
 *       （否则无 `tool:delete` 的上传人会被注解先拦掉）；</li>
 *   <li><b>回收站列表（仅工具）</b> —— 登录即可调用；持 `tool:delete` 看全部，
 *       否则服务层自动收窄为"仅本人"，**故同样不加注解**；</li>
 *   <li><b>批量删 / 彻底删 / 清空</b> —— 管理动作，仍要求 `tool:delete`。</li>
 * </ul>
 *
 * <p>⚠️ **v1.22**：历史版本删除改为**物理删除、不可恢复**，不再有"版本回收站"；
 * 仅「删除整个工具」进回收站。
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
    private final ToolVersionDeleteService toolVersionDeleteService;
    private final ToolPurgeService toolPurgeService;

    /** 软删除工具（进回收站，可恢复）—— 上传人本人亦可 */
    @DeleteMapping("/{id:\\d+}")
    public R<Void> delete(@PathVariable String id) {
        toolTrashService.softDelete(IdUtils.parse(id, "工具ID"));
        return R.ok();
    }

    /** 批量软删除（对不存在 / 不可见 / 已删除的 ID 忽略，整体仍成功）—— 管理动作 */
    @PostMapping("/batch-delete")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<Void> batchDelete(@RequestBody ToolBatchDTO dto) {
        toolTrashService.batchDelete(dto);
        return R.ok();
    }

    /** 已删除工具列表（持 tool:delete 看全部；否则仅本人） */
    @GetMapping("/deleted")
    public R<PageResult<ToolVO>> deleted(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "20") int pageSize,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(toolTrashService.deletedPage(pageNum, pageSize, keyword));
    }

    /** 恢复已删除工具（其现存版本一并回到原状态）—— 上传人本人亦可 */
    @PostMapping("/{id:\\d+}/restore")
    public R<Void> restore(@PathVariable String id) {
        toolTrashService.restore(IdUtils.parse(id, "工具ID"));
        return R.ok();
    }

    /**
     * 删除单个历史版本（**物理删除、不可恢复**，契约 v1.22）。
     * 不允许删最后一个版本（`1005`）；删最新版时后端自动回退 `latest_*`。
     */
    @DeleteMapping("/{id:\\d+}/versions/{versionId:\\d+}")
    public R<Void> deleteVersion(@PathVariable String id, @PathVariable String versionId) {
        toolVersionDeleteService.delete(IdUtils.parse(id, "工具ID"), IdUtils.parse(versionId, "版本ID"));
        return R.ok();
    }

    /** 批量彻底删除（**不可恢复**，连磁盘文件一并删除；仅作用于已软删工具）—— 管理动作 */
    @PostMapping("/batch-purge")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<Void> purge(@RequestBody ToolBatchDTO dto) {
        toolPurgeService.purge(dto);
        return R.ok();
    }

    /** 清空回收站（彻底删除当前用户可见的全部已删除工具；空回收站同样成功）—— 管理动作 */
    @DeleteMapping("/deleted")
    @RequiresPermission(PermissionCode.TOOL_DELETE)
    public R<Void> purgeAll() {
        toolPurgeService.purgeAll();
        return R.ok();
    }
}
