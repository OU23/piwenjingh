package com.example.toolbox.auth.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录响应 VO（契约 §三 LoginVO）。
 */
@Data
@Builder
public class LoginVO {

    private String token;

    /** 有效期（秒） */
    private long expiresIn;

    private CurrentUserVO user;
}
