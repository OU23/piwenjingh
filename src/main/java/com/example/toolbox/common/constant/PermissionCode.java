package com.example.toolbox.common.constant;

/**
 * 权限点编码（契约 §13.1），前后端共用同一套，禁止各自命名或私自新增。
 */
public final class PermissionCode {

    public static final String TOOL_UPLOAD = "tool:upload";
    public static final String TOOL_DOWNLOAD = "tool:download";
    public static final String TOOL_DELETE = "tool:delete";
    public static final String USER_MANAGE = "user:manage";
    public static final String USER_GRANT = "user:grant";
    public static final String DEPT_MANAGE = "dept:manage";
    public static final String TAG_MANAGE = "tag:manage";
    public static final String SYS_SETTING = "sys:setting";

    private PermissionCode() {
    }
}
