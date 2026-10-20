/**
 * 权限点编码（唯一来源：docs/接口契约.md §13.1，前后端共用同一套）
 * 前端只用于按钮 / 菜单显隐，最终判据以后端为准（principles.md R5）。
 */
export const PERMISSION = {
  TOOL_UPLOAD: 'tool:upload',
  TOOL_DOWNLOAD: 'tool:download',
  TOOL_DELETE: 'tool:delete',
  /** M-25 新增（第 9 个）：跨部门查看全部工具 + 调整任意工具归属部门 + 创建时指定部门；**不含内容编辑/删除** */
  TOOL_ASSIGN: 'tool:assign',
  USER_MANAGE: 'user:manage',
  USER_GRANT: 'user:grant',
  DEPT_MANAGE: 'dept:manage',
  TAG_MANAGE: 'tag:manage',
  SYS_SETTING: 'sys:setting',
} as const;

export type PermissionCode = (typeof PERMISSION)[keyof typeof PERMISSION];

/**
 * 新建账号默认授予的权限点（**仅下载**）。
 *
 * 契约 §六 `UserSaveDTO.permissions` 支持"初始权限点"，**不传则默认无权限**；
 * 这里在建号请求中带上，避免新账号建完即"什么都做不了、还需管理员手动补权限"。
 *
 * ⚠️ 需求方 2026-09-24 明确：**新账号默认只有下载能力，不再默认给上传**。
 * 上传能力须由管理员在「分配权限」中显式授予（该默认不改变 M-04 的分配流程）。
 */
export const DEFAULT_NEW_USER_PERMISSIONS: PermissionCode[] = [PERMISSION.TOOL_DOWNLOAD];
