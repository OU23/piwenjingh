/**
 * 测试范围：权限判定与菜单过滤（src/utils/permission.ts + src/constants/menu.ts）
 * 测试类型：单元
 * 测试目的：保证"无权限的入口不渲染"这条显隐规则不被改坏
 * 防回归问题：菜单漏过滤导致越权入口可见、整组为空仍渲染空标题
 * 关键不变量：
 *   - 未声明权限点的入口 = 登录即可见
 *   - 缺少权限点时该入口不出现
 *   - 组内条目全部被过滤后，该组整体不渲染
 * 边界说明：不验证后端鉴权（前端显隐只影响体验，后端为最终依据）
 * 依赖与限制：纯函数，无外部依赖
 */
import { describe, expect, it } from 'vitest';
import { MENU } from '@/constants/menu';
import { PERMISSION } from '@/constants/permissions';
import { filterMenuGroups, hasPermission } from '@/utils/permission';

const ALL_PERMISSIONS = Object.values(PERMISSION);

describe('hasPermission', () => {
  it('未声明权限点的入口对所有登录用户可见', () => {
    expect(hasPermission([], undefined)).toBe(true);
    expect(hasPermission([], null)).toBe(true);
    expect(hasPermission([], '')).toBe(true);
  });

  it('持有该权限点时为真，未持有为假', () => {
    expect(hasPermission([PERMISSION.TOOL_UPLOAD], PERMISSION.TOOL_UPLOAD)).toBe(true);
    expect(hasPermission([PERMISSION.TOOL_DOWNLOAD], PERMISSION.TOOL_UPLOAD)).toBe(false);
  });
});

describe('filterMenuGroups', () => {
  it('无任何权限时只保留登录即可见的入口', () => {
    const groups = filterMenuGroups(MENU, []);
    const items = groups.flatMap((group) => group.items);

    expect(items.map((item) => item.title)).toEqual(['工具列表']);
    // 没有任何权限的用户不应看到"系统管理"分组
    expect(groups.some((group) => group.title === '系统管理')).toBe(false);
  });

  it('持有上传权限时出现"上传工具"，但仍不出现管理入口', () => {
    const items = filterMenuGroups(MENU, [PERMISSION.TOOL_UPLOAD]).flatMap((group) => group.items);
    const titles = items.map((item) => item.title);

    expect(titles).toContain('上传工具');
    expect(titles).not.toContain('用户管理');
    expect(titles).not.toContain('全局设置');
  });

  it('持有全部权限时七个导航项齐全', () => {
    const items = filterMenuGroups(MENU, ALL_PERMISSIONS).flatMap((group) => group.items);

    expect(items).toHaveLength(7);
    expect(items.map((item) => item.title)).toEqual([
      '工具列表',
      '上传工具',
      '已删除工具',
      '用户管理',
      '部门管理',
      '标签管理',
      '全局设置',
    ]);
  });
});
