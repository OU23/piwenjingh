package com.example.toolbox.permission.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权限点条目 VO（契约 §五 PermissionItemVO）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionItemVO {

    /** 权限点编码 */
    private String code;

    /** 名称 */
    private String name;

    /** 说明 */
    private String description;
}
