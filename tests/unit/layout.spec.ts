/**
 * 测试范围：布局 store（src/stores/layout.ts）与 AppLayout 侧边栏折叠交互
 * 测试类型：单元 + 组件（jsdom）
 * 测试目的：保证「侧边栏可折叠、状态持久化、刷新后保持」成立
 * 防回归问题：折叠不生效、刷新丢失状态、顶栏按钮与 store 脱节
 * 关键不变量：
 *   - 默认展开（sidebarCollapsed = false）
 *   - toggleSidebar 翻转状态 + 写 localStorage
 *   - 点击顶栏折叠按钮后：store 状态翻转、侧栏带 is-collapsed 类
 * 边界说明：不验证 CSS 过渡与真实接口
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import AppLayout from '@/components/AppLayout.vue';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import { readSidebarCollapsed, SIDEBAR_COLLAPSED_KEY, useLayoutStore } from '@/stores/layout';

const pageMocks = vi.hoisted(() => ({ listTools: vi.fn(), listTags: vi.fn() }));

vi.mock('@/api/tool', () => ({
  listTools: pageMocks.listTools,
  toolDownloadUrl: (id: string) => `/api/tools/${id}/download`,
}));

vi.mock('@/api/tag', () => ({
  listTags: pageMocks.listTags,
}));

let pinia: Pinia;
let router: Router;

async function mountLayout() {
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'admin',
      realName: '管理员',
      deptId: '1',
      deptName: '研发部',
      permissions: [],
    },
  });

  await router.push('/tools');
  await router.isReady();

  const wrapper = mount(AppLayout, {
    global: { plugins: [pinia, router, ElementPlus] },
  });
  await wrapper.vm.$nextTick();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  localStorage.clear();
  pageMocks.listTools.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
  pageMocks.listTags.mockResolvedValue([]);
});

describe('布局 store（侧边栏折叠）', () => {
  it('默认展开', () => {
    expect(useLayoutStore().sidebarCollapsed).toBe(false);
  });

  it('toggleSidebar 翻转状态并持久化', () => {
    const store = useLayoutStore();

    store.toggleSidebar();
    expect(store.sidebarCollapsed).toBe(true);
    expect(localStorage.getItem(SIDEBAR_COLLAPSED_KEY)).toBe('1');

    store.toggleSidebar();
    expect(store.sidebarCollapsed).toBe(false);
    expect(localStorage.getItem(SIDEBAR_COLLAPSED_KEY)).toBe('0');
  });

  it('readSidebarCollapsed 读取已保存状态（无则默认展开）', () => {
    expect(readSidebarCollapsed()).toBe(false);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, '1');
    expect(readSidebarCollapsed()).toBe(true);
  });
});

describe('AppLayout 侧边栏折叠交互', () => {
  it('点击顶栏折叠按钮：切换 store 状态并给侧栏加 is-collapsed', async () => {
    const wrapper = await mountLayout();
    expect(wrapper.find('.tb-layout__aside').classes()).not.toContain('is-collapsed');

    await wrapper.find('.tb-layout__collapse').trigger('click');

    expect(useLayoutStore().sidebarCollapsed).toBe(true);
    expect(wrapper.find('.tb-layout__aside').classes()).toContain('is-collapsed');
    expect(localStorage.getItem(SIDEBAR_COLLAPSED_KEY)).toBe('1');
  });
});
