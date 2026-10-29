/**
 * 测试范围：权限判定纯函数 —— 全局管理员豁免（src/utils/permission.ts，P9-T3 / 契约 v1.12）
 * 测试类型：单元（纯函数）
 * 测试目的：锁死"持 sys:setting 即不受权限点限制"这一新口径，并保证非管理员行为不变
 * 防回归问题：前端仍按权限点隐藏入口 → 与后端（已豁免）出现"能用但看不见"的不一致；
 *             或反过来把豁免误加到非管理员，导致越权入口暴露
 * 关键不变量：
 *   - 持 `sys:setting` → 任意权限点判定恒 true（与后端 PermissionChecker 一致）
 *   - 不持 `sys:setting` → 行为与既有口径**完全一致**（有权限 true / 无权限 false）
 *   - 未声明权限点的入口（null / undefined）= 登录即可见（回归）
 * 边界说明：只验证显隐判定，不代表安全边界（最终以后端为准，principles.md R5）
 * 依赖与限制：纯函数，无环境依赖
 */
import { describe, expect, it } from 'vitest';
import { GLOBAL_ADMIN_CODE, hasPermission } from '@/utils/permission';

describe('全局管理员豁免（验收 A7）', () => {
  it('全局管理员标识与后端一致：sys:setting', () => {
    expect(GLOBAL_ADMIN_CODE).toBe('sys:setting');
  });

  it('持 sys:setting → 任意权限点均通过（不受权限点限制）', () => {
    const owned = [GLOBAL_ADMIN_CODE];

    expect(hasPermission(owned, 'tool:upload')).toBe(true);
    expect(hasPermission(owned, 'tool:delete')).toBe(true);
    expect(hasPermission(owned, 'user:manage')).toBe(true);
    expect(hasPermission(owned, 'dept:manage')).toBe(true);
    expect(hasPermission(owned, 'sys:setting')).toBe(true);
  });

  it('持 sys:setting 且未持有某权限点 → 仍然通过（豁免而非叠加）', () => {
    const owned = [GLOBAL_ADMIN_CODE, 'tool:download'];

    expect(hasPermission(owned, 'user:manage')).toBe(true);
  });
});

describe('非管理员行为不变（回归）', () => {
  it('有权限 → true；无权限 → false', () => {
    const owned = ['tool:upload'];

    expect(hasPermission(owned, 'tool:upload')).toBe(true);
    expect(hasPermission(owned, 'tool:delete')).toBe(false);
    expect(hasPermission(owned, 'sys:setting')).toBe(false);
  });

  it('空权限集合 → 任意具体权限点均 false', () => {
    expect(hasPermission([], 'tool:upload')).toBe(false);
  });

  it('未声明权限点的入口 = 登录即可见（null / undefined）', () => {
    expect(hasPermission([], null)).toBe(true);
    expect(hasPermission([], undefined)).toBe(true);
  });
});
