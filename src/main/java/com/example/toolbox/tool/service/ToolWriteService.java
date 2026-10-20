package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.constant.ToolType;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.ScopeChecker;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.tag.mapper.TagMapper;
import com.example.toolbox.tool.dto.ToolDeptDTO;
import com.example.toolbox.tool.dto.ToolLinkSaveDTO;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具写入服务（契约 §9.4 / v1.23 §6.1 §6.2 §6.4）：新建（文件 / 链接）/ 追加版本 / 编辑元数据 / 调整归属部门。
 *
 * <p>从 {@link ToolService} 拆出，职责边界：**只做写操作**（读与可见范围校验由
 * {@link ToolService} 提供，避免判定逻辑两处实现）。
 *
 * <p>约束：
 * <ul>
 *   <li>归属部门：默认取当前登录用户；**v1.23 起**持 {@code tool:assign} 者可在创建时指定
 *       （{@code deptId}），或事后经 {@code changeDept} 调整；</li>
 *   <li>名称在「上传人维度」唯一 → 1005；同一工具内版本号唯一 → 1005；</li>
 *   <li>fileKey 无效 / 已占用 → 2005；**链接工具不校验 fileKey、不写版本表**；</li>
 *   <li>fileExt 统一小写（N-07）；版本号不做格式强校验（N-08）；</li>
 *   <li>原则 8：**不在事务中做文件 IO** —— requireFileKeyUsable 只查存在性。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ToolWriteService {

    /** 链接地址长度上限（契约 §6.5 / 数据模型 v1.6） */
    private static final int LINK_URL_MAX_LENGTH = 512;

    private final ToolMapper toolMapper;
    private final ToolVersionMapper toolVersionMapper;
    private final ToolTagMapper toolTagMapper;
    private final TagMapper tagMapper;
    private final DeptMapper deptMapper;
    private final StorageService storageService;
    private final ToolService toolService;
    private final ScopeChecker scopeChecker;
    private final AuditLogger auditLogger;

    /** 新建工具（含首个版本），返回 { toolId, versionId } */
    @Transactional
    public Map<String, String> create(ToolSaveDTO dto) {
        LoginUser user = requireLogin();
        String name = dto.getName().trim();

        // 名称在「上传人维度」唯一 → 1005（v1.21；别人的同名工具不影响）
        if (toolService.findByNameAndUploader(name, user.getUserId(), ToolService.NOT_DELETED) != null) {
            throw new BusinessException(Code.CONFLICT, "工具名称已存在");
        }
        validateScope(dto.getScope());
        requireFileKeyUsable(dto.getFileKey());

        // 归属部门（v1.23）：持 tool:assign 且传了 deptId → 校验后使用；否则取登录用户部门
        Long deptId = resolveCreateDeptId(dto.getDeptId(), user);

        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro(dto.getIntro());
        tool.setReadme(dto.getReadme());
        tool.setFileExt(normalizeExt(dto.getFileExt()));
        tool.setPlatform(dto.getPlatform());
        tool.setDependency(dto.getDependency());
        tool.setScope(dto.getScope());
        tool.setToolType(ToolType.FILE);          // v1.23：文件工具（存量口径不变）
        tool.setLinkUrl(null);
        tool.setDeptId(deptId);
        tool.setUploaderId(user.getUserId());
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        // 先写冗余字段与占位主键，插入拿到 ID 后再回填 latest_version_id
        tool.setLatestVersion(dto.getVersion().trim());
        tool.setFileSize(dto.getFileSize());
        tool.setLatestVersionId(0L);
        try {
            toolMapper.insert(tool);
        } catch (DuplicateKeyException e) {
            // 并发下唯一键 (name, uploader_id) 兜底：绝不把 DB 异常裸露成 500
            throw new BusinessException(Code.CONFLICT, "工具名称已存在");
        }

        ToolVersionDO version = insertVersion(tool.getId(), dto.getVersion(), dto.getFileKey(),
                dto.getFileName(), dto.getFileExt(), dto.getFileSize(), user.getUserId());

        tool.setLatestVersionId(version.getId());
        toolMapper.updateById(tool);
        saveTags(tool.getId(), dto.getTagIds());

        auditLogger.write(user.getUsername(), "TOOL_CREATE",
                "tool:" + tool.getId() + " type=" + ToolType.FILE, "OK");

        Map<String, String> result = new LinkedHashMap<>();
        result.put("toolId", IdUtils.toStr(tool.getId()));
        result.put("versionId", IdUtils.toStr(version.getId()));
        return result;
    }

    /**
     * 新建**链接工具**（契约 v1.23 §6.1，{@code POST /api/tools/link}），返回 { toolId }。
     *
     * <p>与 {@link #create} 的差别：**无文件、无版本** —— 不创建 {@code tb_tool_version} 记录，
     * 按契约 §2.6 的字段约定落库：
     * {@code latest_version_id = 0}、{@code latest_version = ''}、{@code file_size = 0}、{@code file_ext = ''}。
     *
     * <p>其余口径（名称唯一性按上传人、标签关联、归属部门、审计）与文件工具**完全一致**。
     */
    @Transactional
    public Map<String, String> createLink(ToolLinkSaveDTO dto) {
        LoginUser user = requireLogin();
        String name = dto.getName().trim();

        if (toolService.findByNameAndUploader(name, user.getUserId(), ToolService.NOT_DELETED) != null) {
            throw new BusinessException(Code.CONFLICT, "工具名称已存在");
        }
        validateScope(dto.getScope());
        // 协议白名单（http/https）+ 长度，非法 → 1003
        String linkUrl = normalizeLinkUrl(dto.getLinkUrl(), true);
        Long deptId = resolveCreateDeptId(dto.getDeptId(), user);

        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro(dto.getIntro());
        tool.setReadme(dto.getReadme());
        tool.setFileExt("");                       // LINK 无文件格式
        tool.setPlatform(dto.getPlatform());
        tool.setDependency(dto.getDependency());
        tool.setScope(dto.getScope());
        tool.setToolType(ToolType.LINK);
        tool.setLinkUrl(linkUrl);
        tool.setDeptId(deptId);
        tool.setUploaderId(user.getUserId());
        tool.setLatestVersionId(0L);               // LINK 无版本
        tool.setLatestVersion("");
        tool.setFileSize(0L);
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        try {
            toolMapper.insert(tool);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(Code.CONFLICT, "工具名称已存在");
        }

        // ⚠️ 不创建版本记录（链接工具不做版本历史）
        saveTags(tool.getId(), dto.getTagIds());

        auditLogger.write(user.getUsername(), "TOOL_CREATE",
                "tool:" + tool.getId() + " type=" + ToolType.LINK, "OK");

        Map<String, String> result = new LinkedHashMap<>();
        result.put("toolId", IdUtils.toStr(tool.getId()));   // 无 versionId
        return result;
    }

    /** 为已有工具追加新版本，返回版本 ID */
    @Transactional
    public String addVersion(Long toolId, VersionSaveDTO dto) {
        LoginUser user = requireLogin();
        ToolDO tool = toolService.requireVisibleTool(toolId, user);

        String versionNo = dto.getVersion().trim();
        // 同一工具内版本号不可重复 → 1005（v1.22：版本删除即物理删除，唯一性直接由 DB + 本校验保证）
        Long dup = toolVersionMapper.selectCount(Wrappers.<ToolVersionDO>lambdaQuery()
                .eq(ToolVersionDO::getToolId, toolId)
                .eq(ToolVersionDO::getVersion, versionNo));
        if (dup != null && dup > 0) {
            throw new BusinessException(Code.CONFLICT, "该版本号已存在：" + versionNo);
        }
        requireFileKeyUsable(dto.getFileKey());

        ToolVersionDO version;
        try {
            version = insertVersion(toolId, versionNo, dto.getFileKey(),
                    dto.getFileName(), dto.getFileExt(), dto.getFileSize(), user.getUserId());
        } catch (DuplicateKeyException e) {
            // 并发下 DB 唯一键 (tool_id, version) 兜底：绝不把 DB 异常裸露成 500
            throw new BusinessException(Code.CONFLICT, "该版本号已存在：" + versionNo);
        }

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

    /**
     * 编辑工具元数据（契约 v1.23 §6.4）。
     *
     * <p><b>v1.23 三处口径调整</b>：
     * <ol>
     *   <li>{@code version}：**忽略** —— 不再更新 {@code latest_version}（原"传了就改"作废）；</li>
     *   <li>{@code fileExt}：**忽略** —— 本期编辑不改格式（避免 FILE 工具被误改；LINK 恒为空）；</li>
     *   <li>{@code linkUrl}：仅 {@code LINK} 可传，传了即覆盖；{@code FILE} 传了 → 1003。</li>
     * </ol>
     *
     * <p>{@code name} 行为保持现状（接口层不新增"禁止改名"分支）；前端编辑页恒回传原名，
     * 等价于不改名（方案 §3.4：重命名本期不做）。
     */
    @Transactional
    public void update(Long toolId, ToolUpdateDTO dto) {
        LoginUser user = requireLogin();
        ToolDO tool = toolService.requireVisibleTool(toolId, user);
        // 契约 v1.15：编辑判权（上传者本人 或 双权限管理员），在可见性判定之后
        requireEditable(tool, user);

        String newName = dto.getName().trim();
        if (!newName.equals(tool.getName())) {
            // v1.21：改名只与**同一上传人**的未删除工具比对
            ToolDO other = toolService.findByNameAndUploader(newName, tool.getUploaderId(), ToolService.NOT_DELETED);
            if (other != null && !other.getId().equals(toolId)) {
                throw new BusinessException(Code.CONFLICT, "工具名称已存在");
            }
        }
        validateScope(dto.getScope());

        boolean isLink = ToolType.isLink(tool.getToolType());
        // 链接地址：仅 LINK 可传；FILE 传了非空值 → 1003（契约 §6.4）
        if (dto.getLinkUrl() != null && !dto.getLinkUrl().isBlank()) {
            if (!isLink) {
                throw new BusinessException(Code.PARAM_INVALID, "文件工具不支持链接地址");
            }
            tool.setLinkUrl(normalizeLinkUrl(dto.getLinkUrl(), true));
        }

        tool.setName(newName);
        tool.setIntro(dto.getIntro());
        tool.setReadme(dto.getReadme());
        tool.setPlatform(dto.getPlatform());
        tool.setDependency(dto.getDependency());
        tool.setScope(dto.getScope());
        // ⚠️ 不写 fileExt / latestVersion（v1.23：编辑不改格式、不改版本号）
        tool.setUpdateTime(LocalDateTime.now());
        toolMapper.updateById(tool);

        // tagIds 传了才全量覆盖（未传则不动标签，避免误清空）
        if (dto.getTagIds() != null) {
            toolTagMapper.delete(Wrappers.<ToolTagDO>lambdaQuery().eq(ToolTagDO::getToolId, toolId));
            saveTags(toolId, dto.getTagIds());
        }

        auditLogger.write(user.getUsername(), "TOOL_UPDATE", "tool:" + toolId, "OK");
    }

    /**
     * 调整工具**归属部门**（契约 v1.23 §6.2，{@code PUT /api/tools/{id}/dept}）。
     *
     * <p>只更新 {@code tb_tool.dept_id} + {@code update_time}，**不碰** {@code scope}、
     * 定向授权、上传人、版本、{@code latest_*}、下载次数、回收站状态。
     *
     * <p>边界（方案 §2.1）：
     * <ul>
     *   <li>工具不存在 → 1004；**已删除（回收站内）→ 2004**（先恢复再改）；</li>
     *   <li>部门不存在 → 1004；已停用 → 1003；</li>
     *   <li>改成与当前相同 → 直接成功（幂等，不写审计）；</li>
     *   <li>不做"部门变更历史表"，只写审计 {@code TOOL_DEPT_CHANGE}（带 from/to）。</li>
     * </ul>
     */
    @Transactional
    public void changeDept(Long toolId, ToolDeptDTO dto) {
        LoginUser user = requireLogin();
        // ⚠️ 方案歧义处理（§6.2 实现要点 1）：接口注解声明 tool:assign，
        //    但全局 PermissionChecker 对 sys:setting 有短路豁免，而方案明确
        //    "全局管理员若没有该权限点也不应能调" → 此处按**权限点显式**再判定一次，
        //    即本能力只认 tool:assign（管理员需被显式授予该权限点）。
        if (!user.getPermissions().contains(PermissionCode.TOOL_ASSIGN)) {
            throw new BusinessException(Code.FORBIDDEN);
        }
        if (dto == null || dto.getDeptId() == null || dto.getDeptId().isBlank()) {
            throw new BusinessException(Code.PARAM_INVALID, "部门ID不能为空");
        }
        Long targetDeptId = IdUtils.parse(dto.getDeptId(), "部门ID");

        ToolDO tool = toolService.findById(toolId);
        if (tool == null) {
            throw new BusinessException(Code.NOT_FOUND, "工具不存在");
        }
        if (ToolService.isDeleted(tool)) {
            // 回收站里的工具不允许改部门：先恢复再改
            throw new BusinessException(Code.TOOL_DELETED);
        }
        requireEnabledDept(targetDeptId);

        // 幂等：与当前部门相同 → 直接成功
        if (targetDeptId.equals(tool.getDeptId())) {
            return;
        }

        Long oldDeptId = tool.getDeptId();
        ToolDO update = new ToolDO();
        update.setId(toolId);
        update.setDeptId(targetDeptId);
        update.setUpdateTime(LocalDateTime.now());
        toolMapper.updateById(update);

        auditLogger.write(user.getUsername(), "TOOL_DEPT_CHANGE",
                "tool:" + toolId + " from:" + oldDeptId + " to:" + targetDeptId, "OK");
    }

    // ---------------- 内部 ----------------

    /**
     * 「可管理该工具」判权（契约 v1.15 §9.4 编辑口径；**v1.21 起删除 / 恢复版本与工具复用同一把尺子**）：
     * 满足任一即放行 ——
     * <ol>
     *   <li><b>上传者本人</b>：{@code tb_tool.uploader_id == 当前登录用户}；</li>
     *   <li><b>双权限管理员</b>：权限集合<b>同时</b>含 {@code user:manage} 与 {@code user:grant}（**AND**）；</li>
     *   <li>全局管理员（{@code sys:setting}）**短路放行**（复用 {@link ScopeChecker#isGlobalAdmin}，R3 单点）。</li>
     * </ol>
     * 均不满足 → {@code 403}（{@link Code#FORBIDDEN}）。判定不查库：{@link LoginUser} 已携带权限快照。
     *
     * <p>⚠️ **v1.23**：{@code tool:assign} **不在此列** —— 该权限点只带来"改部门 / 看全部"，
     * **不含**内容编辑与删除权（需求方 2026-10-19 明确）。
     */
    public void requireEditable(ToolDO tool, LoginUser user) {
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
     * 创建时的归属部门（v1.23 §6.1 / §6.2）：
     * <ol>
     *   <li>持 {@code tool:assign} **且**传了 {@code deptId} → 校验存在 + 启用后使用；</li>
     *   <li>不持 {@code tool:assign} 却传了 → **忽略**，用自己部门（**不报错**，前端已按权限隐藏）；</li>
     *   <li>都不传 → 用登录用户部门（原行为，完全不变）。</li>
     * </ol>
     * 无部门用户 → 1003（与「部门必填」一致，避免落到 DB NOT NULL 报 500）。
     */
    private Long resolveCreateDeptId(String rawDeptId, LoginUser user) {
        if (rawDeptId != null && !rawDeptId.isBlank()
                && user.getPermissions().contains(PermissionCode.TOOL_ASSIGN)) {
            Long deptId = IdUtils.parse(rawDeptId, "部门ID");
            requireEnabledDept(deptId);
            return deptId;
        }
        if (user.getDeptId() == null) {
            throw new BusinessException(Code.PARAM_INVALID, "当前账号未归属部门，无法创建工具");
        }
        return user.getDeptId();
    }

    /** 部门必须存在（1004）且启用（1003） */
    private DeptDO requireEnabledDept(Long deptId) {
        DeptDO dept = deptMapper.selectById(deptId);
        if (dept == null) {
            throw new BusinessException(Code.NOT_FOUND, "部门不存在");
        }
        if (dept.getStatus() == null || dept.getStatus() != StatusValue.ENABLED) {
            throw new BusinessException(Code.PARAM_INVALID, "目标部门已停用");
        }
        return dept;
    }

    /**
     * 链接地址校验 + 归一化（契约 §2.6 / §6.5）：**协议白名单**只允许 {@code http} / {@code https}，
     * 拒绝 {@code javascript:} / {@code data:} 等；长度 ≤512。
     *
     * <p>空值处理：{@code required = true} → 1003；否则返回 {@code null}（表示"未传，保持原值"）。
     */
    private String normalizeLinkUrl(String raw, boolean required) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            if (required) {
                throw new BusinessException(Code.PARAM_INVALID, "链接地址不能为空");
            }
            return null;
        }
        if (url.length() > LINK_URL_MAX_LENGTH) {
            throw new BusinessException(Code.PARAM_INVALID,
                    "链接地址不能超过 " + LINK_URL_MAX_LENGTH + " 字符");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new BusinessException(Code.PARAM_INVALID, "链接地址仅支持 http / https 协议");
        }
        return url;
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
