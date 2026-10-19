package com.example.toolbox.tool.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工具列表项 / 详情基类字段（契约 §9.3 ToolVO）。
 * ⚠️ **不含 fileKey 等存储内部字段**（契约 §9.3 明确：版本接口不返回存储内部字段）。
 */
@Data
@Builder
public class ToolVO {

    private String id;

    private String name;

    /** 最新版本号 */
    private String latestVersion;

    private String intro;

    /** 文件格式（小写） */
    private String fileExt;

    /** 可见范围：DEPT / PUBLIC */
    private String scope;

    /** 标签列表 */
    private List<TagItem> tags;

    /** 上传人姓名 */
    private String uploaderName;

    /**
     * 上传人 ID（**v1.21 新增**）：供前端按行判定"上传人本人可删除/恢复"，
     * 避免把删除按钮只绑在 `tool:delete` 上（契约 §9.6 v1.21 判权）。
     */
    private String uploaderId;

    /** 归属部门名称 */
    private String deptName;

    /** 最新版本文件大小（字节） */
    private Long fileSize;

    /** 累计下载次数 */
    private Integer downloadCount;

    /** 最近更新时间 */
    private String updateTime;

    /** 标签项（契约 §9.3 `{ id, name }[]`） */
    @Data
    @Builder
    public static class TagItem {
        private String id;
        private String name;
    }
}
