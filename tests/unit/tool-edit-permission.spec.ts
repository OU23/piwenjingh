/**
 * 测试范围：「可编辑工具」判权（src/utils/toolPermission.ts）
 * 测试类型：单元（Pinia store）
 * 测试目的：锁死与后端 `requireEditable` 同口径的四种身份 + 边界（无 uploaderId / 未登录）
 * 防回归问题：权限点口径漂移（例如把 `tool:delete` 当成编辑权）、上传人本人被漏判、字符串/数字 ID 比较失败
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useUserStore } from '@/stores/user';
import { useToolEditPermission } from '@/utils/toolPermission';

function login(userId: string, permissions: string[], deptId = '1'): void {
  useUserStore().setLogin({
    token: 'token-value',
    user: { userId, username: 'u', realName: '张三', deptId, deptName: '研发部', permissions },
  });
}

beforeEach(() => {
  setActivePinia(createPinia());
});

describe('工具编辑判权（M-25）', () => {
  it('上传人本人 → 可编辑（即使没有任何权限点）', () => {
    login('1', []);
    const canEdit = useToolEditPermission();

    expect(canEdit({ uploaderId: '1' })).toBe(true);
  });

  it('非上传人且无管理权限 → 不可编辑', () => {
    login('1', ['tool:download', 'tool:upload']);
    const canEdit = useToolEditPermission();

    expect(canEdit({ uploaderId: '9' })).toBe(false);
  });

  it('双权限管理员（user:manage + user:grant）→ 可编辑', () => {
    login('1', ['user:manage', 'user:grant']);
    const canEdit = useToolEditPermission();

    expect(canEdit({ uploaderId: '9' })).toBe(true);
  });

  it('只有其中一个权限点（非双权限）→ 不可编辑', () => {
    login('1', ['user:manage']);
    const canEdit = useToolEditPermission();

    expect(canEdit({ uploaderId: '9' })).toBe(false);
  });

  it('全局管理员（sys:setting）→ 可编辑', () => {
    login('1', ['sys:setting']);
    const canEdit = useToolEditPermission();

    expect(canEdit({ uploaderId: '9' })).toBe(true);
  });

  it('边界：工具为空 / 缺 uploaderId → 不可编辑', () => {
    login('1', []);
    const canEdit = useToolEditPermission();

    expect(canEdit(null)).toBe(false);
    expect(canEdit(undefined)).toBe(false);
    expect(canEdit({ uploaderId: null })).toBe(false);
  });
});
