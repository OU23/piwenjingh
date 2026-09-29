package com.example.toolbox.tag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标签 DO（数据模型 §3.4 tb_tag）。
 */
@Data
@TableName("tb_tag")
public class TagDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签名称，不可重复 */
    private String name;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
