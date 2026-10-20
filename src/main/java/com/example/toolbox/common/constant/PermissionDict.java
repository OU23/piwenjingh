package com.example.toolbox.common.constant;

import java.util.List;

/**
 * 权限点字典（契约 §13.1，v1.23 起共 **9 个**，按分组）。
 * 字典由后端按此返回，前端不硬编码；权限点编码前后端共用同一套。
 *
 * <p>v1.23（M-25）在「工具」分组新增 {@code tool:assign}（工具归属调整）。
 */
public final class PermissionDict {

    /** 一个权限点条目 */
    public record Item(String code, String name, String description) {
    }

    /** 一个分组 */
    public record Group(String groupCode, String groupName, List<Item> items) {
    }

    /** 全部分组（顺序即返回顺序） */
    public static final List<Group> GROUPS = List.of(
            new Group("tool", "工具", List.of(
                    new Item(PermissionCode.TOOL_UPLOAD, "上传工具", "上传新工具或为已有工具追加版本"),
                    new Item(PermissionCode.TOOL_DOWNLOAD, "下载工具", "下载工具文件"),
                    new Item(PermissionCode.TOOL_DELETE, "删除工具", "删除工具及彻底删除、从已删除列表恢复"),
                    // v1.23（M-25）：跨部门查看全部工具 + 调整任意工具归属部门 + 创建时指定部门；
                    // ⚠️ 不含内容编辑权与删除权
                    new Item(PermissionCode.TOOL_ASSIGN, "工具归属调整",
                            "跨部门查看全部工具、调整工具归属部门、创建工具时指定归属部门"))),
            new Group("user", "用户", List.of(
                    new Item(PermissionCode.USER_MANAGE, "用户管理", "创建账号、修改姓名与归属部门、启用停用"),
                    new Item(PermissionCode.USER_GRANT, "权限分配", "查看与分配用户权限点"))),
            new Group("org", "组织", List.of(
                    new Item(PermissionCode.DEPT_MANAGE, "部门管理", "新建、编辑、启用停用部门"))),
            new Group("category", "分类", List.of(
                    new Item(PermissionCode.TAG_MANAGE, "标签管理", "新建、编辑、删除标签"))),
            new Group("system", "系统", List.of(
                    new Item(PermissionCode.SYS_SETTING, "全局设置", "修改存储根路径与单文件大小上限"))));

    /** 全部权限点编码（用于初始化管理员赋权与合法性校验） */
    public static List<String> allCodes() {
        return GROUPS.stream()
                .flatMap(g -> g.items().stream())
                .map(Item::code)
                .toList();
    }

    /** 编码是否合法 */
    public static boolean isValidCode(String code) {
        return allCodes().contains(code);
    }

    private PermissionDict() {
    }
}
