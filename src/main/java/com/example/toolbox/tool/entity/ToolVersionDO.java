package com.example.toolbox.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具版本表（tb_tool_version，契约 §9 / 数据模型 §3.6）。
 * 文件实体挂在这一层；{@code fileKey} 为存储键，物理路径从不出现（R1）。
 */
@Data
@TableName("tb_tool_version")
public class ToolVersionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属工具 ID */
    private Long toolId;

    /** 版本号，同一工具下不可重复 */
    private String version;

    /** 存储键（物理位置由存储层解析，禁止存物理路径） */
    private String fileKey;

    /** 原始文件名（含扩展名） */
    private String fileName;

    /** 文件格式（小写） */
    private String fileExt;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 上传人 */
    private Long uploaderId;

    /** 该版本下载次数 */
    private Integer downloadCount;

    private LocalDateTime createTime;
}
