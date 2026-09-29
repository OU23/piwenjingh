package com.example.toolbox.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量分配权限入参（契约 §六 UserBatchPermissionDTO，v1.16 新增）。
 *
 * <p>`permissions` 为**最终权限全集（覆盖式）**：入参未含的旧权限会被清除；
 * 传空数组 = 清空这些账号的权限（与单条 {@link UserPermissionDTO} 语义一致）。
 */
@Data
public class UserBatchPermissionDTO {

    /** 目标账号 ID（ID 统一字符串传输，契约 §1.6）；1..N */
    @NotEmpty(message = "userIds 不能为空")
    private List<String> userIds;

    /** 最终权限全集（覆盖式）；空数组合法 = 清空 */
    @NotNull(message = "permissions 不能为 null（全量覆盖，传空数组表示清空）")
    private List<String> permissions;
}
