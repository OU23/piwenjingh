package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工具详情（契约 §9.3 ToolDetailVO = ToolVO 全部字段 + 下列字段）。
 */
@Data
@Builder
public class ToolDetailVO {

    // ---- ToolVO 全部字段 ----
    private String id;
    private String name;
    private String latestVersion;
    private String intro;
    private String fileExt;
    private String scope;
    /** 工具类型：FILE / LINK（v1.23） */
    private String toolType;
    /** 链接地址：仅 LINK 有值，FILE 为 null（v1.23） */
    private String linkUrl;
    private List<ToolVO.TagItem> tags;
    private String uploaderName;
    private String deptName;
    private Long fileSize;
    private Integer downloadCount;
    private String updateTime;

    // ---- 详情扩展字段 ----
    private String readme;
    private String platform;
    private String dependency;
    private String uploaderId;
    private String deptId;
    private String createTime;

    /** 版本列表，**按上传时间倒序** */
    private List<ToolVersionVO> versions;
}
