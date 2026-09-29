package com.example.toolbox.dept.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑部门入参（契约 §四 DeptSaveDTO）。
 */
@Data
public class DeptSaveDTO {

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 50, message = "部门名称不能超过 50 字符")
    private String name;

    @Size(max = 200, message = "备注不能超过 200 字符")
    private String remark;
}
