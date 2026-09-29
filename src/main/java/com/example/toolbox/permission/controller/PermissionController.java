package com.example.toolbox.permission.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.PermissionDict;
import com.example.toolbox.common.result.R;
import com.example.toolbox.permission.vo.PermissionGroupVO;
import com.example.toolbox.permission.vo.PermissionItemVO;
import com.example.toolbox.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限点字典（契约 §五）：GET /api/permissions，需 user:grant。
 * 字典为静态常量，无业务判断，故直接返回（不设 Service 层）。
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    @GetMapping
    @RequiresPermission(PermissionCode.USER_GRANT)
    public R<List<PermissionGroupVO>> list() {
        List<PermissionGroupVO> groups = PermissionDict.GROUPS.stream()
                .map(g -> new PermissionGroupVO(
                        g.groupCode(),
                        g.groupName(),
                        g.items().stream()
                                .map(i -> new PermissionItemVO(i.code(), i.name(), i.description()))
                                .toList()))
                .toList();
        return R.ok(groups);
    }
}
