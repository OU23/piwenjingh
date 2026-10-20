package com.example.toolbox.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.tool.mapper.ToolGrantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 部门可见范围判定 —— **唯一入口**（principles.md R3）。
 *
 * <p>本类是"数据权限"的唯一判定点：列表过滤、详情、下载、编辑、删除**共用同一个判定**。
 * 禁止在任何 Service / Controller 里抄写一遍
 * {@code scope == PUBLIC || deptId == user.deptId || uploaderId == userId}。
 *
 * <p>规则（security.md + 契约 N-04 + **v1.23 §6**），判定顺序固定为：
 * <ol>
 *   <li>{@code sys:setting}（全局管理员）→ 放行；</li>
 *   <li>{@code tool:assign} → 放行（**M-25 新增**：仅工具域，跨部门看全部工具，
 *       不影响用户 / 部门 / 设置等其他域）；</li>
 *   <li>{@code scope = PUBLIC} → 放行；</li>
 *   <li>{@code dept_id == 当前用户 deptId} → 放行；</li>
 *   <li>{@code uploader_id == 当前用户} → 放行（**M-25 新增**：读写都放行 ——
 *       工具被调整到别的部门后，上传人仍能看、能编辑、能删除）；</li>
 *   <li>（**仅只读路径**）定向授权命中 → 放行（M-17）。</li>
 * </ol>
 *
 * <p>⚠️ 第 2 / 第 5 条的边界：
 * <ul>
 *   <li>{@code tool:assign} **不带来**内容编辑权与删除权（编辑 / 删除仍走 {@code requireEditable}）；</li>
 *   <li>上传人**不因此**获得"改部门"能力（那需要 {@code tool:assign}）。</li>
 * </ul>
 *
 * <p>第 5 条"部门隔离对自己上传的工具失效"是**有意为之**（需求 2，非缺陷）。
 *
 * <p>B3 只调用本类，不重写判定逻辑。
 */
@Component
@RequiredArgsConstructor
public class ScopeChecker {

    /** 全局管理员标识权限点（契约 N-04） */
    private static final String GLOBAL_ADMIN_CODE = PermissionCode.SYS_SETTING;

    /** 工具归属调整权限点（M-25 / v1.23）：跨部门查看全部工具 */
    private static final String TOOL_ASSIGN_CODE = PermissionCode.TOOL_ASSIGN;

    /** 定向授权关系（M-17）：仅用于"只读路径"的可见范围扩展，避免 security 包依赖具体实体 */
    private final ToolGrantMapper toolGrantMapper;

    /** 是否全局管理员（不受部门隔离） */
    public boolean isGlobalAdmin(LoginUser user) {
        return user != null && user.getPermissions().contains(GLOBAL_ADMIN_CODE);
    }

    /**
     * 是否持「工具归属调整」权限（M-25 / v1.23）。
     *
     * <p>效果等同全局管理员在**工具可见性**上的表现（看全部工具），但：
     * <ul>
     *   <li>**不**影响其他域（用户 / 部门 / 设置）；</li>
     *   <li>**不**绕过权限点校验（{@link PermissionChecker} 的全局管理员短路与本方法无关）；</li>
     *   <li>**不**带来内容编辑权 / 删除权。</li>
     * </ul>
     */
    public boolean isToolAssign(LoginUser user) {
        return user != null && user.getPermissions().contains(TOOL_ASSIGN_CODE);
    }

    /**
     * 判定：当前用户能否查看 / 下载 / 编辑该资源（**不含**定向授权例外）。
     *
     * <p>顺序见类注释（6 条中前 5 条）。
     *
     * @param resource 资源（工具）的可见属性，可为 null（不存在时视为不可见）
     * @param user     当前登录用户
     * @return true 可见
     */
    public boolean canAccess(ScopedResource resource, LoginUser user) {
        if (resource == null || user == null) {
            return false;
        }
        // ① 全局管理员不受部门隔离
        if (isGlobalAdmin(user)) {
            return true;
        }
        // ② tool:assign 跨部门看全部工具（M-25）
        if (isToolAssign(user)) {
            return true;
        }
        // ③ 公共工具
        if (Scope.PUBLIC.equals(resource.getScope())) {
            return true;
        }
        // ④ 归属部门成员
        Long ownerDeptId = resource.getDeptId();
        Long userDeptId = user.getDeptId();
        if (ownerDeptId != null && ownerDeptId.equals(userDeptId)) {
            return true;
        }
        // ⑤ 上传人本人（M-25）—— 与归属部门无关，读写都放行
        Long uploaderId = resource.getUploaderId();
        return uploaderId != null && uploaderId.equals(user.getUserId());
    }

    /**
     * 判定（**含**定向授权例外，M-17）：在 {@link #canAccess} 基础上，若资源不可见，
     * 再判断当前用户是否被显式授权该工具。仅用于**只读路径**
     * （列表 / 详情 / 版本列表 / 下载 / 下载令牌）；写操作请使用 {@link #canAccess}。
     */
    public boolean canAccessIncludingGrant(ScopedResource resource, LoginUser user) {
        if (canAccess(resource, user)) {
            return true;
        }
        if (resource == null || user == null) {
            return false;
        }
        return toolGrantMapper.countByToolAndUser(resource.getId(), user.getUserId()) > 0;
    }

    /**
     * 判定：不可见即抛 403（契约 §十二：数据不在可见范围 → 1002）。
     * 供详情 / 下载 / 编辑 / 删除等单资源接口调用（不含定向授权例外）。
     */
    public void assertAccessible(ScopedResource resource, LoginUser user) {
        if (!canAccess(resource, user)) {
            throw new BusinessException(Code.FORBIDDEN);
        }
    }

    /** 同 {@link #assertAccessible}，但含定向授权例外（只读路径用） */
    public void assertAccessibleIncludingGrant(ScopedResource resource, LoginUser user) {
        if (!canAccessIncludingGrant(resource, user)) {
            throw new BusinessException(Code.FORBIDDEN);
        }
    }

    /**
     * 列表过滤条件构造 —— **唯一构造点**（不含定向授权例外）。
     *
     * <p>把"公共 + 本部门 + **本人上传**"的过滤**下推到 SQL**（不查出来再过滤，否则分页会错），
     * 并处理全局管理员 / {@code tool:assign}（不加过滤）。
     * 回收站（已删除列表）与写操作过滤调用本方法，禁止自行拼装过滤条件。
     *
     * @param uploaderIdGetter 上传人列（M-25：`ToolDO::getUploaderId`）——
     *                         无部门用户的上传人同样必须能看到自己上传的工具
     */
    public <T> LambdaQueryWrapper<T> applyVisibility(LambdaQueryWrapper<T> wrapper,
                                                     SFunction<T, String> scopeGetter,
                                                     SFunction<T, Long> deptIdGetter,
                                                     SFunction<T, Long> uploaderIdGetter,
                                                     LoginUser user) {
        if (isGlobalAdmin(user) || isToolAssign(user)) {
            return wrapper;
        }
        Long userDeptId = user == null ? null : user.getDeptId();
        Long uid = user == null ? null : user.getUserId();
        if (uid == null) {
            // 无登录主体（不应发生）：退化为只看公共
            return wrapper.eq(scopeGetter, Scope.PUBLIC);
        }
        if (userDeptId == null) {
            // 无部门用户：公共 + 自己上传（否则无部门的上传人看不到自己传的工具）
            return wrapper.and(q -> q.eq(scopeGetter, Scope.PUBLIC)
                    .or().eq(uploaderIdGetter, uid));
        }
        return wrapper.and(q -> q.eq(scopeGetter, Scope.PUBLIC)
                .or().eq(deptIdGetter, userDeptId)
                .or().eq(uploaderIdGetter, uid));
    }

    /**
     * 列表过滤条件构造 —— 含定向授权例外（**只读列表**用，M-17）。
     *
     * <p>在非全局管理员且非 {@code tool:assign} 时，把"被授权给我的工具"也并入可见集（OR 关系）。
     * 回收站 / 写操作列表请使用 {@link #applyVisibility}。
     */
    public <T> LambdaQueryWrapper<T> applyVisibilityIncludingGrant(LambdaQueryWrapper<T> wrapper,
                                                                 SFunction<T, String> scopeGetter,
                                                                 SFunction<T, Long> deptIdGetter,
                                                                 SFunction<T, Long> uploaderIdGetter,
                                                                 LoginUser user) {
        if (isGlobalAdmin(user) || isToolAssign(user)) {
            return wrapper;
        }
        Long userDeptId = user == null ? null : user.getDeptId();
        Long uid = user == null ? null : user.getUserId();
        if (uid == null) {
            return wrapper.eq(scopeGetter, Scope.PUBLIC);
        }
        // uid 来自已认证的 JWT 主体（Long），非前端直接拼接，安全
        String grantExists = "EXISTS (SELECT 1 FROM tb_tool_grant g WHERE g.tool_id = tb_tool.id AND g.user_id = " + uid + ")";
        if (userDeptId == null) {
            return wrapper.and(q -> q.eq(scopeGetter, Scope.PUBLIC)
                    .or().eq(uploaderIdGetter, uid)
                    .or().apply(grantExists));
        }
        return wrapper.and(q -> q.eq(scopeGetter, Scope.PUBLIC)
                .or().eq(deptIdGetter, userDeptId)
                .or().eq(uploaderIdGetter, uid)
                .or().apply(grantExists));
    }
}
