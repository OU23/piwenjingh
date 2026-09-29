package com.example.toolbox.dept.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 部门启用 / 停放入参（契约 §四 DeptStatusDTO）。
 */
@Data
public class DeptStatusDTO {

    /** 1 启用 / 0 停用 */
    @NotNull(message = "status 不能为空")
    private Integer status;
}
