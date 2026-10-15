package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.ScopeChecker;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.tag.mapper.TagMapper;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.dto.ToolUpdateDTO;
import com.example.toolbox.tool.dto.VersionSaveDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolTagDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.mapper.ToolTagMapper;
import com.example.toolbox.tool.mapper.ToolVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具写入服务（契约 §9.4）：新建 / 追加版本 / 编辑元数据。
 *
 * <p>从 {@link ToolService} 拆出，职责边界：**只做写操作**（读与可见范围校验由
 * {@link ToolService} 提供，避免判定逻辑两处实现）。
 *
 * <p>约束：
 * <ul>
 *   <li>归属部门取当前登录用户，**不接受前端传入**；</li>
 *   <li>名称全局唯一 → 1005；同一工具内版本号唯一 → 1005；</li>
 *   <li>fileKey 无效 / 已占用 → 2005；</li>
 *   <li>fileExt 统一小写（N-07）；版本号不做格式强校验（N-08）；</li>
 *   <li>原则 8：**不在事务中做文件 IO** —— requireFileKeyUsable 只查存在性。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ToolWriteService {

    private final ToolMapper toolMapper;
    private final ToolVersionMapper toolVersionMapper;
    private final ToolTagMapper toolTagMapper;
    private final TagMapper tagMapper;
    private final StorageService storageService;
    private final ToolService toolService;
    private final ScopeChecker scopeChecker;
    private final AuditLogger auditLogger;

    /** 新建工具（含首个版本），返回 { toolId, versionId } */
    @Transactional
    public Map<String, String> create(ToolSaveDTO dto) {
        LoginUser user = requireLogin();
        String name = dto.getName().trim();

        // 名称全局唯一 → 1005（兜底；正常路径前端已被 check-name 拦住）
        if (toolService.findByName(name) != null) {
            throw new BusinessException(Code.CONFLICT, "工具名称已存在");
        }
        validateScope(dto.getScope());
        requireFileKeyUsable(dto.getFileKey());

        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro(dto.getIntro());
        tool.setReadme(dto.getReadme());
        tool.setFileExt(normalizeExt(dto.getFileExt()));
        tool.setPlatform(dto.getPlatform());
        tool.setDependency(dto.getDependency());
        tool.setScope(dto.getScope());
        tool.setDeptId(user.getDeptId());          // 归属部门取登录用户，不接受前端传
        tool.setUploaderId(user.getUserId());
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        // 先写冗余字段与占位主键，插入拿到 ID 后再回填 latest_version_id
        tool.setLatestVersion(dto.getVersion().trim());
        tool.setFileSize(dto.getFileSize());
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);

        ToolVersionDO version = insertVersion(tool.getId(), dto.getVersion(), dto.getFileKey(),
                dto.getFileName(), dto.getFileExt(), dto.getFileSize(), user.getUserId());

        tool.setLatestVersionId(version.getId());
        toolMapper.updateById(tool);
        saveTags(tool.getId(), dto.getTagIds());

        auditLogger.write(user.getUsername(), "TOOL_CREATE", "tool:" + tool.getId(), "OK");

        Map<String, String> result = new LinkedHashMap<>();
        result.put("toolId", IdUtils.toStr(tool.getId()));
        result.put("versionId", IdUtils.toStr(version.getId()));
        return result;
    }

    /** 为已有工具追加新版本，返回版本 ID */
    @Transactional
    public String addVersion(Long toolId, VersionSaveDTO dto) {
        LoginUser user = requireLogin();
        ToolDO tool = toolService.requireVisibleTool(toolId, user);

        String versionNo = dto.getVersion().trim();
        // 同一工具内版本号不可重复 → 1005（只校长度与重复，不做格式强校验 —— N-08）
        Long dup = toolVersionMapper.selectCount(Wrappers.<ToolVersionDO>lambdaQuery()
                .eq(ToolVersionDO::getToolId, toolId)
                .eq(ToolVersionDO::getVersion, versionNo));
        if (dup != null && dup > 0) {
            throw new BusinessException(Code.CONFLICT, "该版本号已存在：" + versionNo);
        }
        requireFileKeyUsable(dto.getFileKey());

        ToolVersionDO version = insertVersion(toolId, versionNo, dto.getFileKey(),
                dto.getFileName(), dto.getFileExt(), dto.getFileSize(), user.getUserId());

        // 同事务更新工具上的冗余"最新版本"信息
        tool.setLatestVersionId(version.getId());
        tool.setLatestVersion(versionNo);
        tool.setFileSize(dto.getFileSize());
        tool.setFileExt(normalizeExt(dto.getFileExt()));
        tool.setUpdateTime(LocalDateTime.now());
        toolMapper.updateById(tool);

        auditLogger.write(user.getUsername(), "TOOL_VERSION_ADD", "tool:" + toolId, "OK");
        return IdUtils.toStr(version.getId());
    }

    /** 编辑工具元数据 */
    @Transactional
    public void update(Long toolId, ToolUpdateDTO dto) {
        LoginUser user = requireLogin();
        ToolDO tool = toolService.requireVisibleTool(toolId, user);
        // 契约 v1.15：编辑判权（上传者本人 或 双权限管理员），在可见性判定之后
        requireEditable(tool, user);

        String newName = dto.getName().trim();
        if (!newName.equals(tool.getName())) {
            ToolDO other = toolService.findByName(newName);
            if (other != null && !other.getId().equals(toolId)) {
                throw new BusinessException(Code.CONFLICT, "工具名称已存在");
            }
        }
        validateScope(dto.getScope());

        tool.setName(newName);
        tool.setIntro(dto.getIntro());
        tool.setReadme(dto.getReadme());
        tool.setFileExt(normalizeExt(dto.getFileExt()));
        tool.setPlatform(dto.getPlatform());
        tool.setDependency(dto.getDependency());
        tool.setScope(dto.getScope());
        // version 为工具"当前展示版本号"，不改动版本记录本身
        if (dto.getVersion() != null && !dto.getVersion().isBlank()) {
            tool.setLatestVersion(dto.getVersion().trim());
        }
        tool.setUpdateTime(LocalDateTime.now());
        toolMapper.updateById(tool);

        // tagIds 传了才全量覆盖（未传则不动标签，避免误清空）
        if (dto.getTagIds() != null) {
            toolTagMapper.delete(Wrappers.<ToolTagDO>lambdaQuery().eq(ToolTagDO::getToolId, toolId));
            saveTags(toolId, dto.getTagIds());
        }

        auditLogger.write(user.getUsername(), "TOOL_UPDATE", "tool:" + toolId, "OK");
    }

    // ---------------- 内部 ----------------

    /**
     * 编辑判权（契约 v1.15 §9.4）：满足任一即放行 ——
     * <ol>
     *   <li><b>上传者本人</b>：{@code tb_tool.uploader_id == 当前登录用户}；</li>
     *   <li><b>双权限管理员</b>：权限集合<b>同时</b>含 {@code user:manage} 与 {@code user:grant}（**AND**）；</li>
     *   <li>全局管理员（{@code sys:setting}）**短路放行**（复用 {@link ScopeChecker#isGlobalAdmin}，R3 单点）。</li>
     * </ol>
     * 均不满足 → {@code 403}（{@link Code#FORBIDDEN}）。判定不查库：{@link LoginUser} 已携带权限快照。
     */
    private void requireEditable(ToolDO tool, LoginUser user) {
        if (scopeChecker.isGlobalAdmin(user)) {
            return;
        }
        boolean isUploader = tool.getUploaderId() != null && tool.getUploaderId().equals(user.getUserId());
        Set<String> perms = user.getPermissions() == null ? Set.of() : user.getPermissions();
        boolean isAdmin = perms.contains(PermissionCode.USER_MANAGE) && perms.contains(PermissionCode.USER_GRANT);
        if (!isUploader && !isAdmin) {
            throw new BusinessException(Code.FORBIDDEN);
        }
    }

    /**
     * fileKey 有效性校验（契约 §9.4）：文件不存在 / 已被其他版本占用 → 2005。
     * 只做存在性与占用检查，不做文件 IO（原则 8）。
     */
    private void requireFileKeyUsable(String fileKey) {
        if (!storageService.exists(fileKey)) {
            throw new BusinessException(Code.FILE_KEY_INVALID);
        }
        Long used = toolVersionMapper.selectCount(
                Wrappers.<ToolVersionDO>lambdaQuery().eq(ToolVersionDO::getFileKey, fileKey));
        if (used != null && used > 0) {
            throw new BusinessException(Code.FILE_KEY_INVALID);
        }
    }

    private ToolVersionDO insertVersion(Long toolId, String version, String fileKey,
                                        String fileName, String fileExt, Long fileSize, Long uploaderId) {
        ToolVersionDO v = new ToolVersionDO();
        v.setToolId(toolId);
        v.setVersion(version.trim());
        v.setFileKey(fileKey);
        v.setFileName(fileName);
        v.setFileExt(normalizeExt(fileExt));
        v.setFileSize(fileSize);
        v.setUploaderId(uploaderId);
        // ★ 落点字段必须随写入后端一起落库（契约 M-22 / 数据模型 §5.5）：
        //   文件实际写到 writeBackend（local / oss），storage_backend 就记这个值，
        //   否则读取 / 删除会按"默认 local"路由到错误后端，OSS 文件永远删不掉、下载签不出。
        v.setStorageBackend(storageService.backend().value());
        v.setDownloadCount(0);
        v.setCreateTime(LocalDateTime.now());
        toolVersionMapper.insert(v);
        return v;
    }

    private void validateScope(String scope) {
        if (!Scope.DEPT.equals(scope) && !Scope.PUBLIC.equals(scope)) {
            throw new BusinessException(Code.PARAM_INVALID, "可见范围仅支持 DEPT 或 PUBLIC");
        }
    }

    /** fileExt 统一小写、去点号（N-07） */
    private String normalizeExt(String ext) {
        if (ext == null) {
            return null;
        }
        String e = ext.trim().toLowerCase();
        while (e.startsWith(".")) {
            e = e.substring(1);
        }
        return e;
    }

    private void saveTags(Long toolId, List<String> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        Set<Long> unique = new HashSet<>();
        for (String raw : tagIds) {
            unique.add(IdUtils.parse(raw, "标签ID"));
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long tagId : unique) {
            if (tagMapper.selectById(tagId) == null) {
                throw new BusinessException(Code.NOT_FOUND, "标签不存在：" + tagId);
            }
            ToolTagDO rel = new ToolTagDO();
            rel.setToolId(toolId);
            rel.setTagId(tagId);
            rel.setCreateTime(now);
            toolTagMapper.insert(rel);
        }
    }

    private LoginUser requireLogin() {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        return u;
    }
}
