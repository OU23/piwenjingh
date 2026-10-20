package com.example.toolbox.tool.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 调整工具**归属部门**入参（契约 v1.23 §6.2 ToolDeptDTO）。
 *
 * <p>仅用于 {@code PUT /api/tools/{id}/dept}（权限点 {@code tool:assign}）——
 * **独立接口，不并入** {@code PUT /api/tools/{id}}。
 */
@Data
public class ToolDeptDTO {

    /** 目标部门 ID（必填，string 化的 Long，见契约 §1.6）。不存在 → 1004；已停用 → 1003 */
    @NotBlank(message = "部门ID不能为空")
    private String deptId;
}
