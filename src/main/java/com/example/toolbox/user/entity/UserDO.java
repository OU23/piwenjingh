package com.example.toolbox.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户 DO（数据模型 §3.2 tb_user）。
 * password 只存摘要（BCrypt），禁止明文。
 */
@Data
@TableName("tb_user")
public class UserDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号，全局唯一 */
    private String username;

    /** 密码摘要（BCrypt） */
    private String password;

    private String realName;

    /** 归属部门（一人一部门，不支持兼岗） */
    private Long deptId;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
