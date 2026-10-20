package com.example.toolbox.tool.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.common.result.R;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.tool.dto.DownloadTokenDTO;
import com.example.toolbox.tool.dto.ToolDeptDTO;
import com.example.toolbox.tool.dto.ToolLinkSaveDTO;
import com.example.toolbox.tool.dto.ToolNameCheckDTO;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.dto.ToolUpdateDTO;
import com.example.toolbox.tool.dto.VersionSaveDTO;
import com.example.toolbox.tool.service.DownloadTokenService;
import com.example.toolbox.tool.service.ToolQueryService;
import com.example.toolbox.tool.service.ToolWriteService;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.vo.DownloadTokenVO;
import com.example.toolbox.tool.vo.ToolDetailVO;
import com.example.toolbox.tool.vo.ToolNameCheckVO;
import com.example.toolbox.tool.vo.ToolVO;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.tool.dto.ToolGrantUsersDTO;
import com.example.toolbox.tool.service.ToolGrantNotifier;
import com.example.toolbox.tool.service.ToolGrantService;
import com.example.toolbox.tool.vo.ToolVersionVO;
import com.example.toolbox.tool.vo.UserBriefVO;
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

import java.util.List;
import java.util.Map;

/**
 * 工具（契约 §9.1 / §9.4 /api/tools）。查询类登录即可；写类需 tool:upload。
 * 下载见 {@code DownloadController}（返回文件流，不走统一响应体）。
 */
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;
    private final ToolQueryService toolQueryService;
    private final ToolWriteService toolWriteService;
    private final DownloadTokenService downloadTokenService;
    private final ToolGrantService toolGrantService;
    /** v1.17：定向授权变更通知（只推不关连接） */
    private final ToolGrantNotifier toolGrantNotifier;

    /**
     * 工具列表（检索 + 筛选 + 排序 + 分页，契约 §9.2）。
     *
     * <p><b>v1.23</b>：新增 {@code toolType} 筛选（`ALL` 默认 / `FILE` / `LINK`，非法值 → 1003）。
     */
    @GetMapping
    public R<PageResult<ToolVO>> list(@RequestParam(defaultValue = "1") int pageNum,
                                     @RequestParam(defaultValue = "20") int pageSize,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String tagIds,
                                     @RequestParam(required = false) String fileExt,
                                     @RequestParam(required = false) String scope,
                                     @RequestParam(required = false) String toolType,
                                     @RequestParam(required = false) String sortBy,
                                     @RequestParam(required = false) String sortOrder) {
        return R.ok(toolQueryService.page(pageNum, pageSize, keyword, tagIds, fileExt,
                scope, toolType, sortBy, sortOrder));
    }

    /** 工具详情（含说明与版本列表） */
    @GetMapping("/{id}")
    public R<ToolDetailVO> detail(@PathVariable String id) {
        return R.ok(toolService.detail(IdUtils.parse(id, "工具ID")));
    }

    /** 版本列表 */
    @GetMapping("/{id}/versions")
    public R<List<ToolVersionVO>> versions(@PathVariable String id) {
        return R.ok(toolService.versions(IdUtils.parse(id, "工具ID")));
    }

    /** 同名检查（含 canAppend 可见性分支） */
    @PostMapping("/check-name")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<ToolNameCheckVO> checkName(@RequestBody @Valid ToolNameCheckDTO dto) {
        return R.ok(toolService.checkName(dto));
    }

    /** 新建工具（含首个版本） */
    @PostMapping
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<Map<String, String>> create(@RequestBody @Valid ToolSaveDTO dto) {
        return R.ok(toolWriteService.create(dto));
    }

    /**
     * 新建**链接工具**（契约 v1.23 §6.1，`POST /api/tools/link`）。
     *
     * <p>无文件、无版本：服务端**不创建** {@code tb_tool_version} 记录，出参只有 {@code toolId}。
     * 权限点与文件工具相同（{@code tool:upload}）。
     */
    @PostMapping("/link")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<Map<String, String>> createLink(@RequestBody @Valid ToolLinkSaveDTO dto) {
        return R.ok(toolWriteService.createLink(dto));
    }

    /** 为已有工具追加新版本 */
    @PostMapping("/{id}/versions")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<String> addVersion(@PathVariable String id, @RequestBody @Valid VersionSaveDTO dto) {
        return R.ok(toolWriteService.addVersion(IdUtils.parse(id, "工具ID"), dto));
    }

    /**
     * 编辑工具元数据。
     *
     * <p>⚠️ 契约 v1.15：**不在此处声明权限点注解** —— 判权为「上传者本人 或 双权限管理员
     * （`user:manage` + `user:grant`），全局管理员短路」，由 {@code ToolWriteService.update}
     * 单一入口判定（{@code requireEditable}）。若保留 {@code @RequiresPermission(TOOL_UPLOAD)}，
     * 未持 `tool:upload` 的双权限管理员会被注解先拦掉。
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable String id, @RequestBody @Valid ToolUpdateDTO dto) {
        toolWriteService.update(IdUtils.parse(id, "工具ID"), dto);
        return R.ok();
    }

    /**
     * 调整工具**归属部门**（契约 v1.23 §6.2，`PUT /api/tools/{id}/dept`）。
     *
     * <p>**独立接口，不并入** `PUT /{id}`（前端编辑页"先存内容、再存部门"）。
     * 权限点 {@code tool:assign}；服务层另做一次"必须显式持有该权限点"的判定
     * （`PermissionChecker` 对全局管理员有短路豁免，而契约要求本能力只认权限点）。
     */
    @PutMapping("/{id:\\d+}/dept")
    @RequiresPermission(PermissionCode.TOOL_ASSIGN)
    public R<Void> changeDept(@PathVariable String id, @RequestBody @Valid ToolDeptDTO dto) {
        toolWriteService.changeDept(IdUtils.parse(id, "工具ID"), dto);
        return R.ok();
    }

    /**
     * 换取短时效下载令牌（契约 §9.7 / N-13）。
     * 供非安全上下文（http://IP）下用原生 {@code <a>} 触发下载 ——
     * 导航请求无法携带 Authorization 头，故用 {@code ?token=} 鉴权。
     */
    @PostMapping("/{id}/download-token")
    @RequiresPermission(PermissionCode.TOOL_DOWNLOAD)
    public R<DownloadTokenVO> downloadToken(@PathVariable String id, @RequestBody(required = false) DownloadTokenDTO dto) {
        String versionId = dto == null ? null : dto.getVersionId();
        return R.ok(downloadTokenService.issue(IdUtils.parse(id, "工具ID"), versionId));
    }

    /** 查询某工具已授权的用户（工具侧，契约 §9.8） */
    @GetMapping("/{id}/grants")
    @RequiresPermission(PermissionCode.USER_GRANT)
    public R<List<UserBriefVO>> listGrants(@PathVariable String id) {
        return R.ok(toolGrantService.listUsersForTool(IdUtils.parse(id, "工具ID")));
    }

    /**
     * 保存某工具的授权用户（工具侧，契约 §9.8；全量覆盖）。
     *
     * <p><b>v1.17</b>：保存后向「被影响用户」（**新增 ∪ 被移除**）推送 {@code GRANT_CHANGED}，
     * 使其**无需刷新 / 重登**即可感知可下载工具变化。推送在**事务提交后**执行（只推不关连接），
     * 异常吞掉；**豁免当前操作人**。
     */
    @PutMapping("/{id}/grants")
    @RequiresPermission(PermissionCode.USER_GRANT)
    public R<Void> saveGrants(@PathVariable String id, @RequestBody ToolGrantUsersDTO dto) {
        Long toolId = IdUtils.parse(id, "工具ID");
        Long operatorId = UserContext.get().getUserId();
        List<Long> affected = toolGrantService.saveUsersForTool(toolId, dto.getUserIds(), operatorId);
        toolGrantNotifier.notifyChanged(affected, operatorId);
        return R.ok();
    }
}
