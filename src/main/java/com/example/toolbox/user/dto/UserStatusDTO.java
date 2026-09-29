package com.example.toolbox.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用 / 停用账号入参（契约 §六 UserStatusDTO，v1.4 新增）。
 */
@Data
public class UserStatusDTO {

    /** 1 启用 / 0 停用 */
    @NotNull(message = "status 不能为空")
    private Integer status;
}
