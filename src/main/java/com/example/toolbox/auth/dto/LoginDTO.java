package com.example.toolbox.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录入参（契约 §三 LoginDTO）。密码明文提交，服务端 BCrypt 比对。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "账号不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 强制登录（M-18 / 契约 §3.1，v1.8）：默认 false。
     * true = 若该账号已有活跃会话，作废旧会话并完成本次登录。
     * 注意：force 不得绕过密码校验与账号停用校验。
     */
    private Boolean force;
}
