package com.example.toolbox.permission.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 权限点分组 VO（契约 §五 PermissionGroupVO）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionGroupVO {

    private String groupCode;

    private String groupName;

    private List<PermissionItemVO> items;
}
