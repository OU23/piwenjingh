package com.example.toolbox.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户权限点 DO（数据模型 §3.3 tb_user_permission）。
 * 保存为全量覆盖（先删后插，同一事务内完成）。
 */
@Data
@TableName("tb_user_permission")
public class UserPermissionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 权限点编码，取值见契约 §13.1 */
    private String permissionCode;

    private LocalDateTime createTime;
}
