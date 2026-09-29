package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 授权候选 / 已授权用户简表（契约 §6.1 UserBriefVO）：4 字段。
 * 不暴露密码等敏感字段。
 */
@Data
@Builder
public class UserBriefVO {

    /** 用户 ID（字符串，契约 §1.6） */
    private String id;

    /** 登录账号 */
    private String username;

    /** 姓名 */
    private String realName;

    /** 所属部门名称 */
    private String deptName;
}
