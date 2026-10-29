import { get } from './request';

/** 权限点（docs/接口契约.md §五） */
const PERMISSION_API = {
  dict: '/api/permissions',
} as const;

export interface PermissionItemVO {
  code: string;
  name: string;
  description: string;
}

export interface PermissionGroupVO {
  groupCode: string;
  groupName: string;
  items: PermissionItemVO[];
}

/**
 * 权限点字典（按分组）
 * 权限点清单由后端返回，前端不硬编码（见 docs/rules/02-规范细则/frontend/frontend.md）
 */
export function listPermissions(): Promise<PermissionGroupVO[]> {
  return get<PermissionGroupVO[]>(PERMISSION_API.dict);
}
