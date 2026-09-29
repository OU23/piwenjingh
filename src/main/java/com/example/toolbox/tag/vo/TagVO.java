package com.example.toolbox.tag.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 标签出参（契约 §七 TagVO）。
 */
@Data
@Builder
public class TagVO {

    private String id;

    private String name;

    /** 关联工具数 */
    private Long toolCount;

    private String createTime;
}
