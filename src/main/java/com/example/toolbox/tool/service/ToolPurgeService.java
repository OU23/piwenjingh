package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.ScopeChecker;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.StorageBackend;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.tool.dto.ToolBatchDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolGrantDO;
import com.example.toolbox.tool.entity.ToolTagDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolGrantMapper;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.mapper.ToolTagMapper;
import com.example.toolbox.tool.mapper.ToolVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 彻底删除与清空回收站（契约 §9.6 `batch-purge` / `DELETE /api/tools/deleted`）。
 *
 * <p><b>★ 为什么本类刻意不加 {@code @Transactional}</b>：彻底删除要删**磁盘物理文件**
 * （这是"清理回收站释放磁盘"的根本目的），而 principles.md 原则 8 禁止在事务中做文件 IO。
 * 契约又明确批量接口"**不做部分失败回滚**"。三者的唯一自洽解就是：
 * **不包事务**，按固定顺序逐条执行，失败不阻断整批。
 *
 * <p><b>删除顺序（照 `工具删除与回收站接口说明.md` §2 的落点）</b>：
 * ① 磁盘物理文件 → ② 版本记录 → ③ 标签关联 → ④ 工具记录。
 * 先删文件再删库，是为了让"文件已删、记录还在"这种中间态**可见且可重试**
 * （重跑彻底删除会再次尝试删文件），而不是留下"记录没了、文件还在"的隐形占盘。
 *
 * <p><b>⚠️ 2026-10-16 调整：OSS 落点改为「逻辑删除」</b> ——
 * 后端改用 STS 角色访问 OSS 后，角色**暂无 `oss:DeleteObject` 权限**，物理删除必然 403。
 * 因此 **OSS 落点只删数据库记录、保留 OSS 对象**（本地落点仍物理删除，不受影响）。
 * 待角色补齐 `oss:DeleteObject` 后，把 {@link #purgeFileFor} 的 OSS 分支合并回
 * {@link #deleteFileQuietly} 即恢复"彻底删除"。
 *
 * <p><b>安全性</b>：仅作用于**已软删**的工具 —— 对未软删的 ID **忽略**，防止误删线上工具；
 * 可见范围仍走唯一入口（R3），全局管理员按 N-04 可跨部门。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolPurgeService {

    private final ToolMapper toolMapper;
    private final ToolVersionMapper toolVersionMapper;
    private final ToolTagMapper toolTagMapper;
    private final StorageService storageService;
    private final ToolService toolService;
    private final ToolTrashService toolTrashService;
    private final ScopeChecker scopeChecker;
    private final ToolGrantMapper toolGrantMapper;
    private final AuditLogger auditLogger;

    /** 批量彻底删除（`POST /api/tools/batch-purge`；单个即传单元素数组） */
    public void purge(ToolBatchDTO dto) {
        LoginUser user = requireLogin();
        if (dto == null) {
            throw new BusinessException(Code.PARAM_INVALID, "ids 不能为空");
        }
        for (Long id : IdUtils.parseAll(dto.getIds(), "工具ID")) {
            ToolDO tool = toolMapper.selectById(id);
            // 不存在 / 未软删 / 不可见 → 忽略（契约：防误删线上工具）
            if (tool == null || !ToolService.isDeleted(tool) || !scopeChecker.canAccess(tool, user)) {
                continue;
            }
            purgeOne(tool);
        }
    }

    /** 清空回收站（`DELETE /api/tools/deleted`）：彻底删除当前用户可见的全部已删除工具 */
    public void purgeAll() {
        LoginUser user = requireLogin();
        for (Long id : toolTrashService.deletedIdsVisibleTo(user)) {
            ToolDO tool = toolMapper.selectById(id);
            if (tool != null && ToolService.isDeleted(tool)) {
                purgeOne(tool);
            }
        }
    }

    // ---------------- 内部 ----------------

    /** 单个工具的物理删除：①文件 → ②版本 → ③标签关联 → ④工具记录 */
    private void purgeOne(ToolDO tool) {
        Long toolId = tool.getId();
        List<ToolVersionDO> versions = toolService.listVersions(toolId);

        // ① 物理文件（local 物理删除；oss 逻辑删除，见 purgeFileFor）
        for (ToolVersionDO version : versions) {
            purgeFileFor(version, toolId);
        }
        // ② 版本记录
        toolVersionMapper.delete(Wrappers.<ToolVersionDO>lambdaQuery()
                .eq(ToolVersionDO::getToolId, toolId));
        // ③ 标签关联
        toolTagMapper.delete(Wrappers.<ToolTagDO>lambdaQuery()
                .eq(ToolTagDO::getToolId, toolId));
        // ③.5 定向授权记录（M-17）：随工具彻底删除一并清理（物理删除，无历史价值；软删 / 恢复不动 grant）
        toolGrantMapper.delete(Wrappers.<ToolGrantDO>lambdaQuery()
                .eq(ToolGrantDO::getToolId, toolId));
        // ④ 工具记录
        toolMapper.deleteById(toolId);

        auditLogger.write(operator(), "TOOL_PURGE",
                "tool:" + toolId + " versions:" + versions.size(), "OK");
    }

    /**
     * 删除版本对应的物理文件 —— **按落点区分**。
     *
     * <p>⚠️ **2026-10-16**：**OSS 落点不做物理删除**（只删库记录，OSS 对象保留）。
     * 原因：后端改用 STS 角色访问 OSS，而角色暂无 `oss:DeleteObject` 权限，删对象必 403；
     * 与其"每删一次报一次错"，不如**显式跳过并留审计**。**本地落点行为不变**（仍物理删除）。
     */
    private void purgeFileFor(ToolVersionDO version, Long toolId) {
        if (StorageBackend.of(version.getStorageBackend()) == StorageBackend.OSS) {
            log.info("彻底删除：OSS 落点按逻辑删除处理（保留对象，仅删库记录）toolId={} fileKey={}",
                    toolId, version.getFileKey());
            auditLogger.write(operator(), "TOOL_PURGE_FILE", "tool:" + toolId, "SKIP_OSS_LOGICAL");
            return;
        }
        deleteFileQuietly(version.getFileKey(), toolId);
    }

    /**
     * 删除物理文件：**失败只记日志与审计，不抛出**。
     *
     * <p>理由：一个坏文件（被占用 / 权限不足）不应让整批彻底删除失败 ——
     * 那会导致回收站永远清不掉。库记录照删，残留文件属于"孤儿文件"，
     * 由 N-09 的定时清理兜底（也符合"宁可留文件，不可留脏记录"的取舍）。
     */
    private void deleteFileQuietly(String fileKey, Long toolId) {
        if (fileKey == null || fileKey.isBlank()) {
            return;
        }
        try {
            storageService.delete(fileKey);
        } catch (Exception e) {
            log.warn("彻底删除：物理文件删除失败（已跳过，留待孤儿清理）toolId={} fileKey={} 原因={}",
                    toolId, fileKey, e.getMessage());
            auditLogger.write(operator(), "TOOL_PURGE_FILE", "tool:" + toolId, "FAIL");
        }
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
