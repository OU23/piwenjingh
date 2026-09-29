package com.example.toolbox.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑账号入参（契约 §六 UserUpdateDTO）。
 * 仅 realName 与 deptId 可改；账号不可改（DTO 中无 username 字段，天然无法提交）。
 */
@Data
public class UserUpdateDTO {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名不能超过 50 字符")
    private String realName;

    @NotBlank(message = "归属部门不能为空")
    private String deptId;
}
