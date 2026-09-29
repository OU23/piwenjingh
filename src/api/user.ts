import { download, get, post, put, type DownloadPayload } from './request';
import type { ID, ImportResultVO, PageQuery, PageResult, ToolBriefVO } from './types';

/** 用户（docs/接口契约.md §六） */
const USER_API = {
  base: '/api/users',
  byId: (id: ID) => `/api/users/${id}`,
  status: (id: ID) => `/api/users/${id}/status`,
  permissions: (id: ID) => `/api/users/${id}/permissions`,
  toolGrants: (id: ID) => `/api/users/${id}/tool-grants`,
  /** 导入导出（契约 v1.9 §6.2） */
  importTemplate: '/api/users/import-template',
  importFile: '/api/users/import',
  exportFile: '/api/users/export',
  /** 批量操作（契约 v1.16 §六） */
  batchStatus: '/api/users/batch-status',
  batchPermissions: '/api/users/batch-permissions',
} as const;

export interface UserVO {
  id: ID;
  username: string;
  realName: string;
  deptId: ID;
  deptName: string;
  /** 1 启用 / 0 停用 */
  status: number;
  /** 仅详情返回 */
  permissions?: string[];
  createTime: string;
}

export interface UserListQuery extends PageQuery {
  keyword?: string;
  deptId?: ID;
}

export interface UserSaveDTO {
  username: string;
  realName: string;
  password: string;
  deptId: ID;
  permissions?: string[];
}

/** 编辑账号：仅姓名与归属部门可改 */
export interface UserUpdateDTO {
  realName: string;
  deptId: ID;
}

/** 启用 / 停用账号（契约 §六，v1.4 新增） */
export interface UserStatusDTO {
  /** 1 启用 / 0 停用 */
  status: number;
}

/** 权限点全量覆盖（传空数组 = 清空） */
export interface UserPermissionDTO {
  permissions: string[];
}

export function listUsers(query: UserListQuery): Promise<PageResult<UserVO>> {
  return get<PageResult<UserVO>>(USER_API.base, query as Record<string, unknown>);
}

export function getUser(id: ID): Promise<UserVO> {
  return get<UserVO>(USER_API.byId(id));
}

export function createUser(data: UserSaveDTO): Promise<ID> {
  return post<ID>(USER_API.base, data);
}

export function updateUser(id: ID, data: UserUpdateDTO): Promise<null> {
  return put<null>(USER_API.byId(id), data);
}

/** 启用 / 停用账号（停用后该账号立即无法登录；不得停用当前登录账号自身） */
export function updateUserStatus(id: ID, data: UserStatusDTO): Promise<null> {
  return put<null>(USER_API.status(id), data);
}

export function getUserPermissions(id: ID): Promise<string[]> {
  return get<string[]>(USER_API.permissions(id));
}

export function saveUserPermissions(id: ID, data: UserPermissionDTO): Promise<null> {
  return put<null>(USER_API.permissions(id), data);
}

/**
 * 批量启用 / 停用（契约 v1.16 §六）。
 * 忽略不存在 ID、不做整体回滚；目标含系统初始化管理员且为停用 → 后端 `1005` **整体拒绝**。
 * 「跳过登录人自己」由调用方（前端）负责剔除。
 */
export interface UserBatchStatusDTO {
  userIds: ID[];
  /** 1 启用 / 0 停用 */
  status: number;
}

/** 批量分配权限（契约 v1.16 §六）：`permissions` 为**最终全集（覆盖式）**，传空数组 = 清空 */
export interface UserBatchPermissionDTO {
  userIds: ID[];
  permissions: string[];
}

export function batchUpdateUserStatus(data: UserBatchStatusDTO): Promise<null> {
  return post<null>(USER_API.batchStatus, data);
}

export function batchSaveUserPermissions(data: UserBatchPermissionDTO): Promise<null> {
  return post<null>(USER_API.batchPermissions, data);
}

/**
 * 跨部门工具授权（契约 §6.1，v1.7 新增）
 * - 与权限点是**两个维度**：权限点是功能级（能不能下载），授权是数据级（能下载哪些跨部门工具）
 * - 保存为**全量覆盖**：传空数组 = 清空该用户的全部定向授权
 */
export interface UserToolGrantDTO {
  /** 工具 ID **全量列表**（覆盖式保存；空数组 = 清空） */
  toolIds: ID[];
}

/** 查询该用户被额外授权的工具（用户侧入口） */
export function listUserToolGrants(id: ID): Promise<ToolBriefVO[]> {
  return get<ToolBriefVO[]>(USER_API.toolGrants(id));
}

/** 保存该用户的工具授权（全量覆盖） */
export function saveUserToolGrants(id: ID, data: UserToolGrantDTO): Promise<null> {
  return put<null>(USER_API.toolGrants(id), data);
}

/* ───────────── 批量导入 / 导出（契约 §6.2，v1.9 新增） ───────────── */

/** 导出用户的筛选条件（与列表页一致；不传为全量） */
export interface UserExportQuery {
  keyword?: string;
  deptId?: ID;
}

/** 下载用户导入模板（**Excel 文件流**，走鉴权下载通道：带 `Authorization`，非静态直链） */
export function downloadUserTemplate(): Promise<DownloadPayload> {
  return download(USER_API.importTemplate);
}

/**
 * 批量导入用户
 * - 走**普通 multipart**；字段名**固定为 `file`**；**不手工设置 `Content-Type`**
 * - 行内引用的**部门不存在时由后端自动创建**（结果里的 `createdDeptNames` 会告知）
 */
export function importUsers(file: File): Promise<ImportResultVO> {
  const form = new FormData();
  form.append('file', file);
  return post<ImportResultVO>(USER_API.importFile, form);
}

/** 导出用户（Excel 文件流；`keyword` / `deptId` 对应当前列表筛选） */
export function exportUsers(query?: UserExportQuery): Promise<DownloadPayload> {
  return download(USER_API.exportFile, query as Record<string, unknown> | undefined);
}
