package com.example.toolbox.auth.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 当前登录用户 VO（契约 §三 CurrentUserVO）。禁止放入密码摘要等敏感字段。
 */
@Data
@Builder
public class CurrentUserVO {

    private String userId;

    private String username;

    private String realName;

    private String deptId;

    private String deptName;

    private List<String> permissions;
}
