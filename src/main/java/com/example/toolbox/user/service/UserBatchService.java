package com.example.toolbox.user.service;

import com.example.toolbox.common.constant.PermissionDict;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.SessionService;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.user.dto.UserBatchPermissionDTO;
import com.example.toolbox.user.dto.UserBatchStatusDTO;
import com.example.toolbox.user.dto.UserStatusDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户批量操作（契约 §六 v1.16）：批量启用 / 停用、批量分配权限。
 *
 * <p><b>为什么单独成 bean，而不是放进 {@link UserService}</b>：
 * 批量层要求「**逐账号独立事务、不整体回滚**」，需要每个账号走一次 {@code UserService} 的
 * **事务代理**。若把批量方法写在 {@code UserService} 内部并直接自调用 {@code updateStatus(...)}，
 * Spring 自调用绕过代理 → 内层 {@code @Transactional} **不生效**，事务边界落空。
 * 跨 bean 调用才能保证「每账号各自成事务」。
 *
 * <p>口径（详见 `docs/契约变更说明-用户批量操作（v1.16）.md`）：
 * <ul>
 *   <li>逐账号**复用**单条接口（{@link UserService#updateStatus} / {@link UserService#savePermissions}）的校验与联动；</li>
 *   <li>**忽略不存在 / 已删除 ID**（用户表无软删，"已删除"即"不存在"），不做整体回滚；</li>
 *   <li>**目标含系统初始化管理员 → `1005` 整体拒绝**（**前置整体校验**，避免"改了一半才失败"）；
 *       判定来源 {@link UserService#isSystemAdmin}（系统初始化首个账号）—— **不是** `isGlobalAdmin`；
 *       ⚠️ **停用仅 `status = 0` 时受限**（启用不受限，与单条一致），权限分配**一律受限**；</li>
 *   <li>**跳过当前登录人自己由前端负责**（后端保留单条「不得停用当前登录账号自身」兜底报错）；</li>
 *   <li>本类**不加 `@Transactional`** —— 事务边界在 {@link UserService} 的每个账号调用上。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserBatchService {

    private final UserService userService;
    private final SessionService sessionService;

    /** 批量启用 / 停用（契约 v1.16）：前置整体校验 → 逐账号独立事务。 */
    public void batchUpdateStatus(UserBatchStatusDTO dto) {
        List<Long> targets = resolveTargets(dto.getUserIds());
        Integer status = dto.getStatus();
        if (!StatusValue.isValid(status)) {
            throw new BusinessException(Code.PARAM_INVALID, "status 仅支持 0（停用）或 1（启用）");
        }

        // 前置整体校验：停用系统初始化管理员 → 整体拒绝（**启用不受限**，与单条 updateStatus 一致）
        if (status == StatusValue.DISABLED) {
            for (Long id : targets) {
                if (userService.isSystemAdmin(id)) {
                    throw new BusinessException(Code.CONFLICT, "不能停用管理员账号");
                }
            }
        }

        for (Long id : targets) {
            UserStatusDTO one = new UserStatusDTO();
            one.setStatus(status);
            // 逐账号调用（各自独立事务）：内部含"停用即踢会话 / 关 WS"的既有联动
            userService.updateStatus(id, one);
        }
    }

    /** 批量分配权限（契约 v1.16，**覆盖式**）：前置整体校验 → 逐账号独立事务。 */
    public void batchSavePermissions(UserBatchPermissionDTO dto) {
        List<Long> targets = resolveTargets(dto.getUserIds());
        List<String> codes = dto.getPermissions() == null ? List.of() : List.copyOf(dto.getPermissions());

        // 前置整体校验：权限点编码非法 → 1003（整体拒绝，不进循环）
        for (String code : codes) {
            if (!PermissionDict.isValidCode(code)) {
                throw new BusinessException(Code.PARAM_INVALID, "非法权限点编码：" + code);
            }
        }
        // 前置整体校验：权限分配一律不得涉及系统初始化管理员 → 1005 整体拒绝
        for (Long id : targets) {
            if (userService.isSystemAdmin(id)) {
                throw new BusinessException(Code.CONFLICT, "管理员账号的权限点不可修改");
            }
        }

        Long operatorId = currentOperatorId();
        for (Long id : targets) {
            // updatePermissions = 覆盖保存 + 返回是否实际变化（内部 savePermissions 自身事务）
            boolean changed = userService.updatePermissions(id, codes);
            // M-21（契约 v1.12）：权限实际变化 → 终结该账号旧会话 + 推送 PERMISSION_CHANGED。
            // 豁免：当前操作人（自己改自己不必自我打断）；系统初始化管理员已在上方整体拒绝。
            if (changed && !id.equals(operatorId)) {
                sessionService.forceLogoutByPermissionChange(id);
            }
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 解析目标 ID：去重保序 → **过滤掉不存在 / 已删除的 ID**（忽略而非报错）。
     * ID 格式非法仍按 {@link IdUtils#parse} 抛 `1003`（整体拒绝，不静默）。
     */
    private List<Long> resolveTargets(List<String> rawIds) {
        Set<Long> distinct = new LinkedHashSet<>();
        for (String raw : rawIds) {
            distinct.add(IdUtils.parse(raw, "用户ID"));
        }
        List<Long> targets = new ArrayList<>();
        for (Long id : distinct) {
            if (userService.exists(id)) {
                targets.add(id);
            }
        }
        return targets;
    }

    private Long currentOperatorId() {
        return UserContext.get() == null ? null : UserContext.get().getUserId();
    }
}
