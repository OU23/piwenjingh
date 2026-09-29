package com.example.toolbox.support;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 测试专用实体：模拟 B3 的 ToolDO，只保留可见范围判定所需字段。
 *
 * <p>为什么必须有它：{@code ScopeChecker.applyVisibility} 接收 SFunction 方法引用，
 * MyBatis-Plus 解析时需要实体的 lambda 缓存 —— 该缓存由容器启动时 Mapper 初始化写入。
 * 真实实体（B3 的 ToolDO）天然满足，测试里就用本类等价模拟，避免为了测 A7 提前建 B3 实体。
 */
@Data
@TableName("tb_tool")
public class TestToolEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 可见范围：DEPT / PUBLIC */
    private String scope;

    /** 归属部门 */
    private Long deptId;

    /** 工具名称（供组合条件用例使用） */
    private String name;
}
