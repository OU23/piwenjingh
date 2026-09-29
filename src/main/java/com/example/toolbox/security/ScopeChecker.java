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
 * 禁止在任何 Service / Controller 里抄写一遍 {@code scope == PUBLIC || deptId == user.deptId}。
 *
 * <p>规则（security.md + 契约 N-04）：
 * <ul>
 *   <li>{@code PUBLIC} → 全员可见；</li>
 *   <li>{@code DEPT} → 同部门可见；</li>
 *   <li>**全局管理员不受部门隔离**（契约 §十五 v1.2 N-04：持 {@code sys:setting} 视为全局管理员）；</li>
 *   <li>无部门用户（deptId 为空）只能看 {@code PUBLIC}；</li>
 *   <li>**定向授权例外（M-17,v1.7）**：被显式授权（{@code tb_tool_grant}）的工具，授权用户可见 / 可下载
 *       —— 仅加到"只读路径"判定（{@code *IncludingGrant}），写操作（编辑 / 追加版本 / 删除 / 已删除列表）不受影响。</li>
 * </ul>
 *
 * <p>B3 只调用本类，不重写判定逻辑。
 */
@Component
@RequiredArgsConstructor
public class ScopeChecker {

    /** 全局管理员标识权限点（契约 N-04） */
    private static final String GLOBAL_ADMIN_CODE = PermissionCode.SYS_SETTING;

    /** 定向授权关系（M-17）：仅用于"只读路径"的可见范围扩展，避免 security 包依赖具体实体 */
    private final ToolGrantMapper toolGrantMapper;

    /** 是否全局管理员（不受部门隔离） */
    public boolean isGlobalAdmin(LoginUser user) {
        return user != null && user.getPermissions().contains(GLOBAL_ADMIN_CODE);
    }

    /**
     * 判定：当前用户能否查看 / 下载 / 编辑该资源（**不含**定向授权例外）。
     *
     * @param resource 资源（工具）的可见属性，可为 null（不存在时视为不可见）
     * @param user     当前登录用户
     * @return true 可见
     */
    public boolean canAccess(ScopedResource resource, LoginUser user) {
        if (resource == null || user == null) {
            return false;
        }
        // 全局管理员不受部门隔离
        if (isGlobalAdmin(user)) {
            return true;
        }
        if (Scope.PUBLIC.equals(resource.getScope())) {
            return true;
        }
        Long ownerDeptId = resource.getDeptId();
        Long userDeptId = user.getDeptId();
        return ownerDeptId != null && ownerDeptId.equals(userDeptId);
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
     * <p>把"本部门 + 公共"的过滤下推到 SQL（不查出来再过滤），并处理全局管理员（不加过滤）。
     * 回收站（已删除列表）与写操作过滤调用本方法，禁止自行拼装过滤条件。
     */
    public <T> LambdaQueryWrapper<T> applyVisibility(LambdaQueryWrapper<T> wrapper,
                                                     SFunction<T, String> scopeGetter,
                                                     SFunction<T, Long> deptIdGetter,
                                                     LoginUser user) {
        if (isGlobalAdmin(user)) {
            return wrapper;
        }
        Long userDeptId = user == null ? null : user.getDeptId();
        if (userDeptId == null) {
            // 无部门用户：只能看公共
            return wrapper.eq(scopeGetter, Scope.PUBLIC);
        }
        return wrapper.and(q -> q.eq(scopeGetter, Scope.PUBLIC)
                .or().eq(deptIdGetter, userDeptId));
    }

    /**
     * 列表过滤条件构造 —— 含定向授权例外（**只读列表**用，M-17）。
     *
     * <p>在非全局管理员时，把"被授权给我的工具"也并入可见集（OR 关系）。
     * 回收站 / 写操作列表请使用 {@link #applyVisibility}。
     */
    public <T> LambdaQueryWrapper<T> applyVisibilityIncludingGrant(LambdaQueryWrapper<T> wrapper,
                                                                 SFunction<T, String> scopeGetter,
                                                                 SFunction<T, Long> deptIdGetter,
                                                                 LoginUser user) {
        if (isGlobalAdmin(user)) {
            return wrapper;
        }
        Long userDeptId = user == null ? null : user.getDeptId();
        Long uid = user == null ? null : user.getUserId();
        // uid 来自已认证的 JWT 主体（Long），非前端直接拼接，安全
        String grantExists = "EXISTS (SELECT 1 FROM tb_tool_grant g WHERE g.tool_id = tb_tool.id AND g.user_id = " + uid + ")";
        if (userDeptId == null) {
            return wrapper.and(q -> q.eq(scopeGetter, Scope.PUBLIC).or().apply(grantExists));
        }
        return wrapper.and(q -> q.eq(scopeGetter, Scope.PUBLIC)
                .or().eq(deptIdGetter, userDeptId)
                .or().apply(grantExists));
    }
}
