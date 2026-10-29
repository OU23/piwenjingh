/**
 * 测试范围：路由守卫（src/router/guards.ts）——未登录拦截、已登录回首页、权限点拦截
 * 测试类型：单元（内存路由）
 * 测试目的：保证"未登录进不去受保护页面、无权限点进不去受限页面"的骨架规则成立
 * 防回归问题：未登录直接进入受保护路由、无权限仍可进入受限路由、登录后停留在登录页
 * 关键不变量：
 *   - 未登录访问受保护路由 → 跳登录页并保留来源地址
 *   - 已登录访问登录页 → 回工具列表
 *   - 缺少权限点 → 回工具列表（不进入目标页）
 *   - 具备权限点 → 正常进入
 * 边界说明：不覆盖真实后端鉴权（后端为最终依据）；页面本体在 P2–P4 实现
 * 依赖与限制：jsdom 环境；内存路由历史
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import { routes } from '@/router';
import { setupRouterGuards } from '@/router/guards';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';

function makeRouter(): Router {
  const router = createRouter({ history: createMemoryHistory(), routes });
  setupRouterGuards(router);
  return router;
}

function signIn(permissions: string[]): void {
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'zhangsan',
      realName: '张三',
      deptId: '1',
      deptName: '研发部',
      permissions,
    },
  });
}

beforeEach(() => {
  setActivePinia(createPinia());
});

describe('路由守卫', () => {
  // 本用例会**真实导航**，因而触发目标页组件的首次动态 import 与转换（LoginView / 各页面）。
  // 单跑约 2.3s，但与全量其它 spec 并发时（本机 collect 阶段本身就要数分钟）会被挤到 20s 之外，
  // 属"并发下的转换等待"，与守卫逻辑无关 → 单独放宽超时（**不涉及任何断言调整**）。
  it(
    '未登录访问受保护路由时跳转登录页，并保留来源地址',
    async () => {
      const router = makeRouter();

      await router.push('/tools');

      expect(router.currentRoute.value.name).toBe('login');
      expect(router.currentRoute.value.query.redirect).toBe('/tools');
    },
    60_000,
  );

  it('已登录访问登录页时回到工具列表', async () => {
    const router = makeRouter();
    signIn([]);

    await router.push('/login');

    expect(router.currentRoute.value.name).toBe('tool-list');
  });

  it('缺少权限点时不进入受限页面', async () => {
    const router = makeRouter();
    signIn([PERMISSION.TOOL_DOWNLOAD]);

    await router.push('/upload');

    expect(router.currentRoute.value.name).toBe('tool-list');
  });

  it('具备权限点时正常进入受限页面', async () => {
    const router = makeRouter();
    signIn([PERMISSION.TOOL_UPLOAD]);

    await router.push('/upload');

    expect(router.currentRoute.value.name).toBe('tool-upload');
  });

  it('初始化页与 404 页无需登录即可访问', async () => {
    const router = makeRouter();

    await router.push('/init');
    expect(router.currentRoute.value.name).toBe('init');

    await router.push('/not-exist-page');
    expect(router.currentRoute.value.name).toBe('not-found');
  });
});
