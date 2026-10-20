package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.ScopeChecker;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.tool.dto.ToolBatchDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.vo.ToolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 删除、回收站与恢复（契约 §9.6）。
 *
 * <p>三条关键口径：
 * <ol>
 *   <li><b>软删除</b>：置 `deleted = 1`，记录保留 —— 恢复成本为零；
 *       **v1.22**：历史版本不做软删（删除即物理删除），恢复工具不涉及版本状态；</li>
 *   <li><b>批量接口幂等</b>：对不存在 / 不可见 / 状态不符的 ID **一律忽略**，
 *       整体仍返回 `code = 0`（契约明确不做部分失败回滚）—— 故批量路径**不抛 403/1004**，
 *       只逐条 `continue`；单个接口才做严格报错；</li>
 *   <li><b>可见范围</b>：一律走 {@link ToolService#applyVisibility} / {@link ScopeChecker}
 *       唯一入口（R3），全局管理员按 N-04 不受部门隔离，可跨部门删除 / 恢复。</li>
 * </ol>
 *
 * <p>物理删除（彻底删除 / 清空回收站）见 {@link ToolPurgeService} —— 那里有磁盘 IO，
 * 与本类（纯 DB）分开，避免"事务里夹文件操作"。
 */
@Service
@RequiredArgsConstructor
public class ToolTrashService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ToolMapper toolMapper;
    private final ToolService toolService;
    private final ToolWriteService toolWriteService;
    private final ScopeChecker scopeChecker;
    private final AuditLogger auditLogger;

    // ---------------- 软删除 ----------------

    /**
     * 软删除单个工具（`DELETE /api/tools/{id}`）。
     *
     * <p>幂等（验收 C5）：已删除的工具再次删除**直接成功**，不报错也不产生脏数据 ——
     * 这与批量版「状态不符则忽略」保持一致，避免同一语义在单 / 批两条路径上表现不同。
     */
    @Transactional
    public void softDelete(Long toolId) {
        LoginUser user = requireLogin();
        ToolDO tool = toolService.requireVisibleToolIncludingDeleted(toolId, user);
        // v1.21：删除判权与编辑同口径（上传人本人 / 双权限 / 全局管理员），不再要求 tool:delete
        toolWriteService.requireEditable(tool, user);
        if (ToolService.isDeleted(tool)) {
            return;
        }
        updateDeleted(toolId, ToolService.DELETED);
        auditLogger.write(operator(), "TOOL_DELETE", "tool:" + toolId, "OK");
    }

    /** 批量软删除（`POST /api/tools/batch-delete`）：忽略不存在 / 不可见 / 已删除的 ID */
    @Transactional
    public void batchDelete(ToolBatchDTO dto) {
        LoginUser user = requireLogin();
        List<Long> ids = parseIds(dto);
        for (Long id : ids) {
            ToolDO tool = toolMapper.selectById(id);
            if (tool == null || ToolService.isDeleted(tool) || !scopeChecker.canAccess(tool, user)) {
                continue;   // 契约：忽略，不报错
            }
            updateDeleted(id, ToolService.DELETED);
            auditLogger.write(operator(), "TOOL_BATCH_DELETE", "tool:" + id, "OK");
        }
    }

    // ---------------- 已删除列表 ----------------

    /**
     * 已删除工具列表（`GET /api/tools/deleted`）：可见范围内 `deleted = 1`，支持关键词 + 分页。
     *
     * <p><b>v1.21 可见性</b>：持 `tool:delete` 者看**全部**（原有口径）；无该权限的登录用户
     * 也可调用，但**只返回本人上传的**（上传人可自助恢复误删的版本，避免"能删不能救"）。
     */
    public PageResult<ToolVO> deletedPage(int pageNum, int pageSize, String keyword) {
        LoginUser user = requireLogin();
        int pn = pageNum <= 0 ? 1 : pageNum;
        int ps = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<ToolDO> w = Wrappers.lambdaQuery();
        w.eq(ToolDO::getDeleted, ToolService.DELETED);
        if (ToolService.canManageTrash(user)) {
            // ★ 可见范围唯一入口：全局管理员不加条件 → 契约要求"返回全部部门的已删除工具"自动成立
            toolService.applyVisibility(w, ToolDO::getScope, ToolDO::getDeptId, ToolDO::getUploaderId, user);
        } else {
            w.eq(ToolDO::getUploaderId, user.getUserId());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(ToolDO::getName, kw).or().like(ToolDO::getIntro, kw));
        }
        // 回收站按"最近删除的更靠前"展示：id 倒序即最近删除优先（本期不引入 deletedTime，见 N-11）
        w.orderByDesc(ToolDO::getId);

        Page<ToolDO> page = toolMapper.selectPage(new Page<>(pn, ps), w);
        return PageResult.of(page.getRecords().stream().map(toolService::toVO).toList(),
                page.getTotal(), pn, ps);
    }

    // ---------------- 恢复 ----------------

    /**
     * 恢复已删除工具（`POST /api/tools/{id}/restore`）。
     *
     * <p>不存在的记录、或**未删除**的记录 → `1004`（契约 §9.6）；
     * v1.22 起历史版本**不再软删**（删除即物理删除），因此恢复工具只需把工具置回未删除，
     * 其现存版本与 `latest_*` 自然回到原状态。
     */
    @Transactional
    public void restore(Long toolId) {
        LoginUser user = requireLogin();
        ToolDO tool = toolService.requireVisibleToolIncludingDeleted(toolId, user);
        // v1.21：恢复判权与删除同口径（上传人本人 / 双权限 / 全局管理员）
        toolWriteService.requireEditable(tool, user);
        if (!ToolService.isDeleted(tool)) {
            throw new BusinessException(Code.NOT_FOUND, "工具不存在或未处于已删除状态");
        }
        updateDeleted(toolId, ToolService.NOT_DELETED);
        auditLogger.write(operator(), "TOOL_RESTORE", "tool:" + toolId, "OK");
    }

    // ---------------- 供彻底删除复用 ----------------

    /** 当前用户可见范围内的**全部已删除工具 ID**（供"清空回收站"用） */
    public List<Long> deletedIdsVisibleTo(LoginUser user) {
        LambdaQueryWrapper<ToolDO> w = Wrappers.lambdaQuery();
        w.select(ToolDO::getId);
        w.eq(ToolDO::getDeleted, ToolService.DELETED);
        toolService.applyVisibility(w, ToolDO::getScope, ToolDO::getDeptId, ToolDO::getUploaderId, user);
        return toolMapper.selectList(w).stream().map(ToolDO::getId).toList();
    }

    /** 解析批量 ID：非空校验 + 去重 + 统一由 {@code IdUtils} 解析（格式非法 → 1003） */
    private List<Long> parseIds(ToolBatchDTO dto) {
        if (dto == null) {
            throw new BusinessException(Code.PARAM_INVALID, "ids 不能为空");
        }
        return IdUtils.parseAll(dto.getIds(), "工具ID");
    }

    private void updateDeleted(Long toolId, int value) {
        ToolDO update = new ToolDO();
        update.setId(toolId);
        update.setDeleted(value);
        update.setUpdateTime(LocalDateTime.now());
        toolMapper.updateById(update);
    }

    private LoginUser requireLogin() {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        return user;
    }

    private String operator() {
        LoginUser user = UserContext.get();
        return user == null ? null : user.getUsername();
    }
}
