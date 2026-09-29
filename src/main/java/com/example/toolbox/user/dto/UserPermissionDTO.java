package com.example.toolbox.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 保存用户权限点入参（契约 §六 UserPermissionDTO）。
 * permissions 为**全量列表**（覆盖式保存）；传空数组 = 清空权限。
 * 注意：字段必填（不能为 null），空数组合法。
 */
@Data
public class UserPermissionDTO {

    @NotNull(message = "permissions 不能为 null（全量覆盖，传空数组表示清空）")
    private List<String> permissions;
}
