package com.example.toolbox.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.user.entity.UserPermissionDO;
import com.example.toolbox.user.mapper.UserPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 系统管理员身份判定（M-22 v1.13，从 {@link UserService} 抽出）。
 *
 * <p><b>定义</b>：系统初始化时创建的首个账号，即 `tb_user_permission` 中持 `sys:setting` 且
 * `user_id` 最小者。初始化永远先建管理员、后建其他账号，故管理员恒为最小 id 的
 * sys:setting 持有者；且管理员不可被删除、其 `sys:setting` 不可被移除（见 {@link UserService} 多处保护），
 * 该身份稳定。
 *
 * <p><b>与 `sys:setting` 权限点解耦</b>：本服务只识别"那一个初始化管理员账号"，
 * 其他即便被赋予 `sys:setting` 的普通账号（如被赋全权的 `test1`）**不是**系统管理员，
 * 仍正常可见、可管理——这正是 M-22 v1.13 修正要解决的核心问题。
 *
 * <p>注意：本服务**不含**"`sys:setting` 持有者 = 全局管理员（v1.12 全权豁免）"的判定，
 * 那是 {@link UserService#isGlobalAdmin}，二者语义不同、互不复用。
 */
@Service
@RequiredArgsConstructor
public class AdminIdentityService {

    private final UserPermissionMapper userPermissionMapper;

    /**
     * 系统管理员账号 ID；返回 null = 当前无任何 sys:setting 持有者（未初始化或异常态），
     * 调用方据此「不隐藏 / 不保护」。
     */
    public Long getSystemAdminId() {
        UserPermissionDO p = userPermissionMapper.selectOne(
                Wrappers.<UserPermissionDO>lambdaQuery()
                        .eq(UserPermissionDO::getPermissionCode, PermissionCode.SYS_SETTING)
                        .orderByAsc(UserPermissionDO::getUserId)
                        .last("LIMIT 1"));
        return p == null ? null : p.getUserId();
    }

    /**
     * 某账号是否为系统管理员（初始化创建的首个账号）。
     * 用于：列表 / 导出隐藏、停用保护、权限点修改保护、工具授权保护 —— 仅此一个账号，
     * 不含其他被赋予 sys:setting 的普通账号。
     */
    public boolean isSystemAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        Long adminId = getSystemAdminId();
        return adminId != null && adminId.equals(userId);
    }
}
