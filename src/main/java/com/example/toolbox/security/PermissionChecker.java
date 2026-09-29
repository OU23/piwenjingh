package com.example.toolbox.security;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 判权唯一入口（principles.md R3）。
 * 所有权限判定走此处，禁止散落在业务方法里写 if-else。
 * B1 地基仅做基础判定；B2 起按需补充全局管理员豁免等规则，仍收口于此。
 */
@Component
@RequiredArgsConstructor
public class PermissionChecker {

    /**
     * 全局管理员判定 —— **唯一入口在 {@link ScopeChecker}**（持 `sys:setting`）。
     * M-21 / 契约 v1.12 起，权限点判定同样对该类账号**豁免**。
     */
    private final ScopeChecker scopeChecker;

    public boolean hasPermission(LoginUser user, String requiredCode) {
        if (user == null) {
            return false;
        }
        // 全局管理员不受权限点限制（契约 §6.1 / §9.4，v1.12）：权限点是给普通账号的约束
        if (scopeChecker.isGlobalAdmin(user)) {
            return true;
        }
        return user.getPermissions().contains(requiredCode);
    }

    /**
     * 权限不足即抛真 403（1003 语义由全局处理器映射为 HTTP 403）。
     *
     * <p>供**无法走 {@code PermissionInterceptor} 的场景**复用，
     * 如下载接口的「Bearer + {@code ?token=}」双通道鉴权（契约 §9.5 v1.3）——
     * 该接口的权限判定必须在控制器内显式完成，但**判定规则本身仍收口于此**，
     * 不得在控制器里另写一套 if-else（R3）。
     */
    public void require(LoginUser user, String requiredCode) {
        if (!hasPermission(user, requiredCode)) {
            throw new BusinessException(Code.FORBIDDEN);
        }
    }

    /**
     * 任一权限点即可（M-17：GET /api/users 放宽 user:manage 或 user:grant）。
     * 全部缺失 → 真 403。
     */
    public void requireAny(LoginUser user, String... requiredCodes) {
        if (user == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        for (String code : requiredCodes) {
            if (hasPermission(user, code)) {
                return;
            }
        }
        throw new BusinessException(Code.FORBIDDEN);
    }
}
