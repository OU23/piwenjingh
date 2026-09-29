package com.example.toolbox.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具标签关联表（tb_tool_tag，契约 §七 / 数据模型 §3.7）。
 * 多对多：删除标签只删关联，不删工具。
 */
@Data
@TableName("tb_tool_tag")
public class ToolTagDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long toolId;

    private Long tagId;

    private LocalDateTime createTime;
}
