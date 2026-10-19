package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.ScopeChecker;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.StorageBackend;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.mapper.ToolVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 历史版本删除（契约 §9.6 **v1.22**）。
 *
 * <p><b>★ 口径：物理删除、不可恢复</b> —— 历史版本不再进回收站：
 * 删文件（本地物理删 / OSS 逻辑删，沿用 2026-10-16 M-22 口径）+ 删数据库记录。
 * 只有「删除整个工具」才进回收站（{@link ToolTrashService}）。
 *
 * <p>两条约束：
 * <ol>
 *   <li><b>不允许删最后一个版本</b> → {@code 1005}，提示"请直接删除整个工具（会进回收站）" ——
 *       避免出现"工具还在、但没有任何版本"的空壳；</li>
 *   <li>删的若是**最新版** → {@code latest_*} 回退到按上传时间倒序的下一个版本。</li>
 * </ol>
 *
 * <p>判权与编辑同口径（上传人本人 / `user:manage`+`user:grant` / 全局管理员），由
 * {@link ToolWriteService#requireEditable} 单点判定。
 *
 * <p><b>为什么不在事务里</b>：要删物理文件（原则 8），故与方法 {@code ToolPurgeService} 同策略 ——
 * 不包事务，先删文件再删记录；文件删除失败只记日志与审计，不阻断记录删除
 * （"宁可留孤儿文件，不可留脏记录"）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolVersionDeleteService {

    private final ToolMapper toolMapper;
    private final ToolVersionMapper toolVersionMapper;
    private final ToolService toolService;
    private final ToolWriteService toolWriteService;
    private final StorageService storageService;
    private final ScopeChecker scopeChecker;
    private final AuditLogger auditLogger;

    /** 删除单个版本（`DELETE /api/tools/{id}/versions/{versionId}`，物理删除、不可恢复） */
    public void delete(Long toolId, Long versionId) {
        LoginUser user = requireLogin();
        ToolDO tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BusinessException(Code.NOT_FOUND, "工具不存在");
        }
        if (ToolService.isDeleted(tool)) {
            throw new BusinessException(Code.TOOL_DELETED);
        }
        scopeChecker.assertAccessible(tool, user);
        toolWriteService.requireEditable(tool, user);

        ToolVersionDO version = toolVersionMapper.selectById(versionId);
        // 版本不存在，或不属于该工具（防跨工具删别人的文件）→ 1004
        if (version == null || !toolId.equals(version.getToolId())) {
            throw new BusinessException(Code.NOT_FOUND, "版本不存在");
        }

        // 不允许删最后一个版本：否则会留下"有工具、无版本"的空壳，恢复也无从恢复
        Long total = toolVersionMapper.selectCount(Wrappers.<ToolVersionDO>lambdaQuery()
                .eq(ToolVersionDO::getToolId, toolId));
        if (total != null && total <= 1) {
            throw new BusinessException(Code.CONFLICT, "该工具仅剩一个版本，请直接删除整个工具（会进回收站）");
        }

        deleteFileQuietly(version, toolId);
        toolVersionMapper.deleteById(versionId);

        // 删的是最新版 → 回退到按上传时间倒序的下一个版本
        if (Objects.equals(tool.getLatestVersionId(), versionId)) {
            refreshLatest(tool);
        }
        auditLogger.write(operator(), "TOOL_VERSION_DELETE", "version:" + versionId, "OK");
    }

    /**
     * 删除版本对应的物理文件 —— 按落点区分。
     * ⚠️ OSS 落点**不做物理删除**（M-22 口径：后端角色无 `oss:DeleteObject`，保留对象只删记录）；
     * 本地落点仍物理删除。文件删除失败只告警，不阻断记录删除。
     */
    private void deleteFileQuietly(ToolVersionDO version, Long toolId) {
        if (StorageBackend.of(version.getStorageBackend()) == StorageBackend.OSS) {
            log.info("删除版本：OSS 落点按逻辑删除处理（保留对象，仅删库记录）toolId={} fileKey={}",
                    toolId, version.getFileKey());
            auditLogger.write(operator(), "TOOL_VERSION_DELETE_FILE", "tool:" + toolId, "SKIP_OSS_LOGICAL");
            return;
        }
        try {
            storageService.delete(version.getFileKey());
        } catch (Exception e) {
            log.warn("删除版本：物理文件删除失败（已跳过，留待孤儿清理）toolId={} fileKey={} 原因={}",
                    toolId, version.getFileKey(), e.getMessage());
            auditLogger.write(operator(), "TOOL_VERSION_DELETE_FILE", "tool:" + toolId, "FAIL");
        }
    }

    /** 重算 `latest_*`：删除后至少还剩一个版本（上面已拦截），故不会出现空列表 */
    private void refreshLatest(ToolDO tool) {
        List<ToolVersionDO> remain = toolService.listVersions(tool.getId());
        if (remain.isEmpty()) {
            return; // 理论不可达：调用前已校验 total > 1
        }
        ToolVersionDO latest = remain.get(0);
        toolMapper.update(null, Wrappers.<ToolDO>lambdaUpdate()
                .eq(ToolDO::getId, tool.getId())
                .set(ToolDO::getLatestVersionId, latest.getId())
                .set(ToolDO::getLatestVersion, latest.getVersion())
                .set(ToolDO::getFileSize, latest.getFileSize())
                .set(ToolDO::getUpdateTime, LocalDateTime.now()));
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
