package com.example.toolbox.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 一键初始化入参（契约 §十一 InitDTO）。
 */
@Data
public class InitDTO {

    @NotBlank(message = "管理员账号不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "管理员账号须为 4-32 位字母/数字/下划线")
    private String adminUsername;

    @NotBlank(message = "管理员密码不能为空")
    @Size(min = 6, max = 32, message = "管理员密码须为 6-32 位")
    private String adminPassword;

    @NotBlank(message = "管理员姓名不能为空")
    @Size(max = 50, message = "管理员姓名不能超过 50 字符")
    private String adminRealName;
}
