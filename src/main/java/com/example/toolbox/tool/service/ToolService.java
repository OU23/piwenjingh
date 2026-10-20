package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.DateTimeUtils;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.ScopeChecker;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.tag.entity.TagDO;
import com.example.toolbox.tag.mapper.TagMapper;
import com.example.toolbox.tool.dto.ToolNameCheckDTO;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.dto.ToolUpdateDTO;
import com.example.toolbox.tool.dto.VersionSaveDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolTagDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.mapper.ToolTagMapper;
import com.example.toolbox.tool.mapper.ToolVersionMapper;
import com.example.toolbox.tool.vo.ToolDetailVO;
import com.example.toolbox.tool.vo.ToolNameCheckVO;
import com.example.toolbox.tool.vo.ToolVO;
import com.example.toolbox.tool.vo.ToolVersionVO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import com.example.toolbox.user.mapper.UserMapper;
import com.example.toolbox.user.mapper.UserPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具与版本服务（契约 §9.1 / §9.4）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>可见范围一律经 {@link ScopeChecker}（R3），**禁止在本类重写判定**；</li>
 *   <li>归属部门默认取**当前登录用户**；**v1.23 起**持 {@code tool:assign} 者可指定 /
 *       调整（{@code ToolSaveDTO.deptId} 与 {@code PUT /api/tools/{id}/dept}）；</li>
 *   <li>对外 VO **不返回 fileKey** 等存储内部字段（契约 §9.3）；</li>
 *   <li>已删除工具不可编辑 / 追加版本 → 2004（契约 §9.6）；</li>
 *   <li>原则 8：**不在事务中做文件 IO**（本类的写操作都是纯 DB，fileKey 只做存在性校验）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ToolService {

    /** 软删除标记：0 正常 */
    public static final int NOT_DELETED = 0;

    /** 软删除标记：1 已删除（进回收站） */
    public static final int DELETED = 1;

    private final ToolMapper toolMapper;
    private final ToolVersionMapper toolVersionMapper;
    private final ToolTagMapper toolTagMapper;
    private final TagMapper tagMapper;
    private final UserMapper userMapper;
    private final UserPermissionMapper userPermissionMapper;
    private final DeptMapper deptMapper;
    private final StorageService storageService;
    private final ScopeChecker scopeChecker;
    private final ToolVoAssembler voAssembler;
    private final AuditLogger auditLogger;

    // ---------------- §9.4 同名检查 ----------------

    /**
     * 同名检查（契约 §9.4，**v1.21 起按上传人判定**）。
     *
     * <p>只查「当前登录人 + 未删除」的同名工具 —— 别人的同名工具不再命中（GitHub 模式）。
     * 回收站里自己的同名工具由 {@code inTrash} / {@code trashToolId} 单独返回，
     * 供前端提示"删除旧工具 / 恢复旧工具"（§9.4 同名分支）。
     *
     * <p>分支互斥：命中未删除工具时 {@code inTrash} 恒为 false（无需提示清理回收站）。
     */
    public ToolNameCheckVO checkName(ToolNameCheckDTO dto) {
        LoginUser user = requireLogin();
        String name = dto.getName().trim();

        ToolDO existing = findByNameAndUploader(name, user.getUserId(), NOT_DELETED);
        if (existing != null) {
            return ToolNameCheckVO.builder()
                    .existed(true)
                    .toolId(IdUtils.toStr(existing.getId()))
                    .latestVersion(existing.getLatestVersion())
                    .canAppend(true)
                    .inTrash(false)
                    .trashToolId(null)
                    .build();
        }
        ToolDO trashed = findByNameAndUploader(name, user.getUserId(), DELETED);
        return ToolNameCheckVO.builder()
                .existed(false)
                .toolId(null)
                .latestVersion(null)
                .canAppend(false)
                .inTrash(trashed != null)
                .trashToolId(trashed == null ? null : IdUtils.toStr(trashed.getId()))
                .build();
    }

    // ---------------- §9.1 详情 / 版本列表 ----------------

    /** 工具详情（含 readme 与版本列表） */
    public ToolDetailVO detail(Long toolId) {
        LoginUser user = requireLogin();
        ToolDO tool = requireVisibleForRead(toolId, user);
        List<ToolVersionDO> versions = listVersions(toolId);
        return voAssembler.toDetailVO(tool, versions);
    }

    /** 版本列表（按上传时间倒序） */
    public List<ToolVersionVO> versions(Long toolId) {
        LoginUser user = requireLogin();
        ToolDO tool = requireVisibleForRead(toolId, user);
        return listVersions(toolId).stream()
                .map(v -> voAssembler.toVersionVO(v, v.getId().equals(tool.getLatestVersionId())))
                .toList();
    }

    // ---------------- 供 B4（删除 / 回收站）与下载复用 ----------------

    /** 按 ID 取工具（**不过滤**可见范围；调用方自行判定） */
    public ToolDO findById(Long toolId) {
        return toolMapper.selectById(toolId);
    }

    /**
     * 该工具的全部版本（倒序）。
     * v1.22 起版本**不做软删**（删除即物理删除），故此处无需过滤删除标记。
     */
    public List<ToolVersionDO> listVersions(Long toolId) {
        return toolVersionMapper.selectList(Wrappers.<ToolVersionDO>lambdaQuery()
                .eq(ToolVersionDO::getToolId, toolId)
                .orderByDesc(ToolVersionDO::getCreateTime)
                .orderByDesc(ToolVersionDO::getId));
    }

    /** 单个版本（不存在返回 null） */
    public ToolVersionDO findVersion(Long versionId) {
        return toolVersionMapper.selectById(versionId);
    }

    /**
     * 用户部门 ID（供 {@code ?token=} 下载通道重建上下文用）。
     * 实时读库，保证"改了归属部门立刻生效"。
     */
    public Long findUserDeptId(Long userId) {
        if (userId == null) {
            return null;
        }
        UserDO u = userMapper.selectById(userId);
        return u == null ? null : u.getDeptId();
    }

    /**
     * 用户权限点集合（供 {@code ?token=} 下载通道重建上下文用）。
     * 实时读库，保证"改了权限立刻生效"（与登录口径一致）。
     */
    public Set<String> findUserPermissions(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return new HashSet<>(userPermissionMapper.selectList(
                        Wrappers.<UserPermissionDO>lambdaQuery().eq(UserPermissionDO::getUserId, userId))
                .stream().map(UserPermissionDO::getPermissionCode).toList());
    }

    /**
     * 名称命中（**同上传人 + 指定删除状态**，契约 §9.4 v1.21）。
     * 不同上传人的同名工具互不影响（GitHub 模式）。
     */
    public ToolDO findByNameAndUploader(String name, Long uploaderId, int deleted) {
        if (name == null || uploaderId == null) {
            return null;
        }
        return toolMapper.selectOne(Wrappers.<ToolDO>lambdaQuery()
                .eq(ToolDO::getName, name)
                .eq(ToolDO::getUploaderId, uploaderId)
                .eq(ToolDO::getDeleted, deleted)
                .last("LIMIT 1"));
    }

    /**
     * 取"可见且未删除"的工具。
     * 不存在 → 1004；已删除 → 2004；不在可见范围 → **403**（1002）。
     * 可见性判定走唯一入口，不在此处写 if。
     */
    public ToolDO requireVisibleTool(Long toolId, LoginUser user) {
        ToolDO tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BusinessException(Code.NOT_FOUND, "工具不存在");
        }
        if (tool.getDeleted() != null && tool.getDeleted() != NOT_DELETED) {
            throw new BusinessException(Code.TOOL_DELETED);
        }
        scopeChecker.assertAccessible(tool, user);
        return tool;
    }

    /**
     * 只读路径的「可见范围」判定（**含**定向授权例外，M-17）：列表 / 详情 / 版本列表 / 下载 / 下载令牌 复用。
     * 存在 + 未删除 + 可见（含授权）；已删除 → 2004；不可见 → 403。
     * 写操作（编辑 / 追加版本 / 删除）请使用 {@link #requireVisibleTool}（不含授权例外，见验收 A9）。
     */
    public ToolDO requireVisibleForRead(Long toolId, LoginUser user) {
        ToolDO tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BusinessException(Code.NOT_FOUND, "工具不存在");
        }
        if (tool.getDeleted() != null && tool.getDeleted() != NOT_DELETED) {
            throw new BusinessException(Code.TOOL_DELETED);
        }
        scopeChecker.assertAccessibleIncludingGrant(tool, user);
        return tool;
    }

    /**
     * 列表过滤（**含**定向授权例外，M-17）：供只读列表使用，委托 ScopeChecker 唯一入口。
     * 回收站 / 写操作列表请使用 {@link #applyVisibility}。
     *
     * <p><b>v1.23（M-25）</b>：新增 {@code uploaderIdGetter} 参数 —— 上传人恒可见必须
     * **下推到 SQL**（{@code OR uploader_id = ?}），否则分页会错。
     */
    public <T> LambdaQueryWrapper<T> applyVisibilityIncludingGrant(LambdaQueryWrapper<T> wrapper,
                                                                 SFunction<T, String> scopeGetter,
                                                                 SFunction<T, Long> deptIdGetter,
                                                                 SFunction<T, Long> uploaderIdGetter,
                                                                 LoginUser user) {
        return scopeChecker.applyVisibilityIncludingGrant(wrapper, scopeGetter, deptIdGetter,
                uploaderIdGetter, user);
    }

    /** 列表项 VO 组装（薄委托；实现见 {@link ToolVoAssembler}） */
    public ToolVO toVO(ToolDO tool) {
        return voAssembler.toVO(tool);
    }

    /**
     * 取"可见"的工具，**不区分是否已删除**（契约 §9.6 删除 / 恢复 / 彻底删除用）。
     *
     * <p>为什么不能在删除链路复用 {@link #requireVisibleTool}：后者对已删除的工具直接抛 2004，
     * 而回收站场景**必须能操作已删除的工具**（恢复、彻底删除）。
     * 因此这里只做「存在性 + 可见范围」两步：不存在 → 1004；不在可见范围 → **403**（1002）。
     *
     * <p>可见范围判定仍走 {@link ScopeChecker} 唯一入口；全局管理员按 N-04 不受部门隔离，
     * 因而可删除 / 恢复 / 彻底删除**任意部门**的工具。
     */
    public ToolDO requireVisibleToolIncludingDeleted(Long toolId, LoginUser user) {
        ToolDO tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BusinessException(Code.NOT_FOUND, "工具不存在");
        }
        scopeChecker.assertAccessible(tool, user);
        return tool;
    }

    /** 是否已软删除（null 视为未删除） */
    public static boolean isDeleted(ToolDO tool) {
        return tool != null && tool.getDeleted() != null && tool.getDeleted() != NOT_DELETED;
    }

    /**
     * 是否持回收站**管理**权限（`tool:delete`）：
     * 决定回收站列表看全部还是只看自己、以及"彻底删除 / 清空"按钮是否可用。
     * v1.21：上传人本人的删除/恢复**不依赖**该权限点。
     */
    public static boolean canManageTrash(LoginUser user) {
        return user != null && user.getPermissions() != null
                && user.getPermissions().contains(PermissionCode.TOOL_DELETE);
    }

    /**
     * 可见范围过滤条件的唯一构造入口（列表接口复用，禁止自行拼装）。
     *
     * <p><b>v1.23（M-25）</b>：新增 {@code uploaderIdGetter}（`ToolDO::getUploaderId`）——
     * 上传人恒可见/可管，且该条件**必须下推 SQL**，不得查出来再过滤。
     */
    public <T> LambdaQueryWrapper<T> applyVisibility(LambdaQueryWrapper<T> wrapper,
                                                     SFunction<T, String> scopeGetter,
                                                     SFunction<T, Long> deptIdGetter,
                                                     SFunction<T, Long> uploaderIdGetter,
                                                     LoginUser user) {
        return scopeChecker.applyVisibility(wrapper, scopeGetter, deptIdGetter, uploaderIdGetter, user);
    }

    // ---------------- 内部 ----------------

    private LoginUser requireLogin() {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        return u;
    }
}
