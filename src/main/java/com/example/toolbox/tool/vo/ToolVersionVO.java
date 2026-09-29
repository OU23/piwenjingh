package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 工具版本（契约 §9.3 ToolVersionVO）。
 * ⚠️ 不返回 fileKey 等存储内部字段。
 */
@Data
@Builder
public class ToolVersionVO {

    private String id;

    /** 版本号，如 v1.2 */
    private String version;

    /** 文件原始名称 */
    private String fileName;

    private String fileExt;

    private Long fileSize;

    private String uploaderName;

    /** 上传时间 */
    private String uploadTime;

    /** 该版本下载次数 */
    private Integer downloadCount;

    /** 是否最新版本 */
    private Boolean isLatest;
}
