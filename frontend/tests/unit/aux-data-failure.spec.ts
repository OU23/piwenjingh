/**
 * 测试范围：辅助数据（部门字典 / 标签字典）加载失败时的可见性
 *   - src/views/admin/UserView.vue（归属部门下拉）
 *   - src/views/tool/ToolListView.vue（标签筛选）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"辅助数据失败不得静默"的口径（验收 A3）
 * 防回归问题：字典接口挂了，下拉变成空的，界面一句解释都没有 —— 用户以为"系统里就没有部门/标签"
 * 关键不变量：
 *   - 字典加载失败时页面出现可读提示，且主列表照常渲染（不阻断）
 *   - 提示里带上后端给出的原因
 *   - 用户管理页在这种状态下打开"新建账号"，弹窗内必须先说明部门不可用
 * 边界说明：不验证重试按钮的真实请求（联调验证）
 * 依赖与限制：接口模块为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import ToolListView from '@/views/tool/ToolListView.vue';
import UserView from '@/views/admin/UserView.vue';

const mocks = vi.hoisted(() => ({
  listTools: vi.fn(),
  listTags: vi.fn(),
  listUsers: vi.fn(),
  listDepts: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listTools: mocks.listTools,
  toolDownloadUrl: (id: string, versionId?: string) =>
    `/api/tools/${id}/download${versionId ? `?versionId=${versionId}` : ''}`,
}));
vi.mock('@/api/tag', () => ({ listTags: mocks.listTags }));
vi.mock('@/api/user', () => ({
  listUsers: mocks.listUsers,
  getUser: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  getUserPermissions: vi.fn(),
  saveUserPermissions: vi.fn(),
  // P8 新增的导入导出入口：本文件只渲染页面，**仅需桩存在**（夹具对齐，未改任何断言）
  downloadUserTemplate: vi.fn(),
  exportUsers: vi.fn(),
  importUsers: vi.fn(),
}));
vi.mock('@/api/dept', () => ({
  listDepts: mocks.listDepts,
  createDept: vi.fn(),
  updateDept: vi.fn(),
  updateDeptStatus: vi.fn(),
}));

let pinia: Pinia;
let router: Router;

async function mountWith(component: unknown, path: string, permissions: string[]): Promise<VueWrapper> {
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'admin',
      realName: '管理员',
      deptId: '1',
      deptName: '研发部',
      permissions,
    },
  });

  await router.push(path);
  await router.isReady();

  const wrapper = mount(component as never, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.clearAllMocks();
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
});

describe('标签字典加载失败（工具列表）', () => {
  it('给出可见提示，且工具列表本身照常渲染', async () => {
    mocks.listTools.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
    mocks.listTags.mockRejectedValue(new Error('标签服务暂时不可用'));

    const wrapper = await mountWith(ToolListView, '/tools', ['tool:download']);

    expect(wrapper.text()).toContain('标签服务暂时不可用');
    expect(wrapper.text()).toContain('暂不能按标签筛选');
    // 主列表不受影响：不因为标签失败而进入错误态
    expect(wrapper.text()).not.toContain('加载失败，请稍后重试');
  });

  it('标签正常时不出现提示', async () => {
    mocks.listTools.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
    mocks.listTags.mockResolvedValue([{ id: '1', name: '数据处理', toolCount: 1, createTime: '2026-09-01 10:00:00' }]);

    const wrapper = await mountWith(ToolListView, '/tools', ['tool:download']);

    expect(wrapper.text()).not.toContain('暂不能按标签筛选');
  });
});

describe('部门字典加载失败（用户管理）', () => {
  it('筛选栏给出可见提示与重试入口', async () => {
    mocks.listUsers.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
    mocks.listDepts.mockRejectedValue(new Error('部门服务暂时不可用'));

    const wrapper = await mountWith(UserView, '/admin/users', ['user:manage', 'user:grant']);

    expect(wrapper.text()).toContain('部门服务暂时不可用');
    expect(wrapper.text()).toContain('暂不能按部门筛选');
    expect(wrapper.text()).toContain('重试');
  });

  it('此状态下打开「新建账号」，弹窗内先说明部门不可用', async () => {
    mocks.listUsers.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
    mocks.listDepts.mockRejectedValue(new Error('部门服务暂时不可用'));

    const wrapper = await mountWith(UserView, '/admin/users', ['user:manage']);

    const createButton = wrapper.findAll('button').find((item) => item.text().includes('新建账号'));
    expect(createButton).toBeTruthy();
    await createButton?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('部门服务暂时不可用，请关闭弹窗后重试');
  });

  it('部门正常时不出现提示', async () => {
    mocks.listUsers.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
    mocks.listDepts.mockResolvedValue([{ id: '1', name: '研发部', status: 1, userCount: 2, createTime: '2026-09-01 10:00:00' }]);

    const wrapper = await mountWith(UserView, '/admin/users', ['user:manage']);

    expect(wrapper.text()).not.toContain('暂不能按部门筛选');
  });
});
