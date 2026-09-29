package com.example.toolbox.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具定向授权（跨部门可见 / 下载例外，数据模型 §3.9 / M-17）。
 *
 * <p>不做软删除：取消授权即物理删除该行（授权关系无历史价值）；
 * {@code uk_tool_user} 保证同一「工具 + 用户」只有一条，天然幂等。
 */
@Data
@TableName("tb_tool_grant")
public class ToolGrantDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被授权的工具 ID（不建外键，引用完整性靠应用层 + 级联清理） */
    private Long toolId;

    /** 被授权的用户 ID */
    private Long userId;

    /** 最近一次授权的操作人（管理员）用户 ID */
    private Long grantBy;

    /** 授权时间（建表已设 DEFAULT CURRENT_TIMESTAMP，插入亦显式赋值，杜绝零日期） */
    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
