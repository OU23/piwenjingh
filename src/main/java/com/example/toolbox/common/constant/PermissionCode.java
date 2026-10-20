package com.example.toolbox.common.constant;

/**
 * 权限点编码（契约 §13.1），前后端共用同一套，禁止各自命名或私自新增。
 */
public final class PermissionCode {

    public static final String TOOL_UPLOAD = "tool:upload";
    public static final String TOOL_DOWNLOAD = "tool:download";
    public static final String TOOL_DELETE = "tool:delete";

    /**
     * 工具归属调整（M-25 / 契约 v1.23 §13.1，第 9 个权限点）。
     *
     * <p>能力：① 跨部门查看**全部**工具（仅工具域，不影响用户 / 部门 / 设置等其他域）；
     * ② 调整**任意工具**的 {@code dept_id}（{@code PUT /api/tools/{id}/dept}）；
     * ③ 创建工具时指定归属部门。
     * <p>**不含**内容编辑权、删除权、彻底删除 / 清空回收站。
     */
    public static final String TOOL_ASSIGN = "tool:assign";
    public static final String USER_MANAGE = "user:manage";
    public static final String USER_GRANT = "user:grant";
    public static final String DEPT_MANAGE = "dept:manage";
    public static final String TAG_MANAGE = "tag:manage";
    public static final String SYS_SETTING = "sys:setting";

    private PermissionCode() {
    }
}
