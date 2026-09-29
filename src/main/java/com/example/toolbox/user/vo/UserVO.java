package com.example.toolbox.user.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 用户 VO（契约 §六）。权限点仅详情返回（withPermissions 控制）。
 * 禁止返回密码摘要等敏感字段。
 */
@Data
@Builder
public class UserVO {

    private String id;

    private String username;

    private String realName;

    private String deptId;

    private String deptName;

    /** 1 启用 / 0 停用 */
    private Integer status;

    /** 权限点编码（仅详情返回） */
    private List<String> permissions;

    private String createTime;
}
