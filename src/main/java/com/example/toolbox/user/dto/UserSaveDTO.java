package com.example.toolbox.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建账号入参（契约 §六 UserSaveDTO）。
 * 注意：不含权限点必填约束——permissions 选填，不传则默认无权限。
 */
@Data
public class UserSaveDTO {

    @NotBlank(message = "账号不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "账号须为 4-32 位字母/数字/下划线")
    private String username;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名不能超过 50 字符")
    private String realName;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 6, max = 32, message = "初始密码须为 6-32 位")
    private String password;

    @NotBlank(message = "归属部门不能为空")
    private String deptId;

    /** 初始权限点；不传则默认无权限 */
    private List<String> permissions;
}
