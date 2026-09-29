package com.example.toolbox.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量启用 / 停用账号入参（契约 §六 UserBatchStatusDTO，v1.16 新增）。
 *
 * <p>口径见 `契约变更说明-用户批量操作（v1.16）.md`：逐账号复用 {@code updateStatus}
 * 的校验与联动；忽略不存在 ID；目标含系统初始化管理员（**仅停用**）→ `1005` 整体拒绝。
 */
@Data
public class UserBatchStatusDTO {

    /** 目标账号 ID（ID 统一字符串传输，契约 §1.6）；1..N */
    @NotEmpty(message = "userIds 不能为空")
    private List<String> userIds;

    /** 1 启用 / 0 停用 */
    @NotNull(message = "status 不能为空")
    private Integer status;
}
