import type { MenuGroup } from '@/constants/menu';

/**
 * 权限判定（纯函数，便于单测）
 * 前端只做显隐；安全的最终依据在后端（principles.md R5）。
 */

/**
 * 全局管理员标识权限点（契约 §6.1 / §9.4 **v1.12**）：
 * 持 `sys:setting` 即视为全局管理员，**不受权限点限制** —— 与后端 `PermissionChecker` 口径一致。
 */
export const GLOBAL_ADMIN_CODE = 'sys:setting';

/**
 * 未声明权限点的入口 = 登录即可见；
 * **全局管理员不受权限点限制**（契约 v1.12：权限点是给普通账号的约束）。
 */
export function hasPermission(owned: readonly string[], code?: string | null): boolean {
  if (!code) {
    return true;
  }
  if (owned.includes(GLOBAL_ADMIN_CODE)) {
    return true;
  }
  return owned.includes(code);
}

/** 按权限点过滤菜单：无权限的项不渲染；整组为空则整组不渲染 */
export function filterMenuGroups(groups: readonly MenuGroup[], owned: readonly string[]): MenuGroup[] {
  return groups
    .map((group) => ({
      title: group.title,
      items: group.items.filter((item) => hasPermission(owned, item.permission ?? null)),
    }))
    .filter((group) => group.items.length > 0);
}
