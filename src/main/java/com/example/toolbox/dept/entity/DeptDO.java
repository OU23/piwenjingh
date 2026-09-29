package com.example.toolbox.dept.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门 DO（数据模型 §3.1 tb_dept）。
 * 扁平结构：parent_id 保留以备扩展，本期恒为 null。
 */
@Data
@TableName("tb_dept")
public class DeptDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 部门名称，不可重复 */
    private String name;

    private String remark;

    /** 1 启用 / 0 停用 */
    private Integer status;

    /** 上级部门 ID；当前扁平结构恒为 null */
    private Long parentId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
