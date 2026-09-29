/**
 * 测试范围：系统初始化页（src/views/login/InitView.vue）与登录页（src/views/login/LoginView.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"已初始化则不允许再初始化"与"登录失败统一提示"两条口径
 * 防回归问题：已初始化仍展示初始化表单、重复初始化无提示、登录错误提示区分原因（可探测账号）
 * 关键不变量：
 *   - 已初始化 → 直接跳登录页，不渲染表单
 *   - 提交遇"已初始化"业务码 → 提示并跳登录页
 *   - 登录失败统一提示"账号或密码错误"
 *   - 登录成功 → 令牌与权限点写入全局状态
 * 边界说明：不覆盖真实接口；跳转后的页面渲染由对应页面的测试覆盖
 * 依赖与限制：接口模块为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { BizError } from '@/api/request';
import { BizCode } from '@/api/types';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import InitView from '@/views/login/InitView.vue';
import LoginView from '@/views/login/LoginView.vue';

const mocks = vi.hoisted(() => ({
  getInitStatus: vi.fn(),
  initSystem: vi.fn(),
  login: vi.fn(),
  listTools: vi.fn(),
}));

vi.mock('@/api/system', () => ({
  getInitStatus: mocks.getInitStatus,
  initSystem: mocks.initSystem,
}));

vi.mock('@/api/auth', () => ({
  login: mocks.login,
  logout: vi.fn(async () => null),
  getCurrentUser: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listTools: mocks.listTools,
  toolDownloadUrl: (id: string) => `/api/tools/${id}/download`,
}));

vi.mock('@/api/tag', () => ({
  listTags: vi.fn(async () => []),
}));

let pinia: Pinia;
let router: Router;

async function mountView(component: unknown, path: string): Promise<VueWrapper> {
  await router.push(path);
  await router.isReady();
  const wrapper = mount(component as never, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  mocks.getInitStatus.mockReset();
  mocks.initSystem.mockReset();
  mocks.login.mockReset();
  mocks.listTools.mockReset();
  mocks.listTools.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
});

describe('系统初始化页', () => {
  it('已初始化：直接跳登录页，不渲染表单', async () => {
    mocks.getInitStatus.mockResolvedValue({ initialized: true });
    const wrapper = await mountView(InitView, '/init');

    expect(router.currentRoute.value.name).toBe('login');
    expect(wrapper.find('#init-username').exists()).toBe(false);
  });

  it('未初始化：渲染初始化表单', async () => {
    mocks.getInitStatus.mockResolvedValue({ initialized: false });
    const wrapper = await mountView(InitView, '/init');

    expect(wrapper.find('#init-username').exists()).toBe(true);
    expect(wrapper.find('#init-password').exists()).toBe(true);
    expect(wrapper.text()).toContain('完成初始化');
  });

  it('校验：两次密码不一致时不提交', async () => {
    mocks.getInitStatus.mockResolvedValue({ initialized: false });
    const wrapper = await mountView(InitView, '/init');

    await wrapper.get('#init-username').setValue('admin001');
    await wrapper.get('#init-realname').setValue('管理员');
    await wrapper.get('#init-password').setValue('123456');
    await wrapper.get('#init-confirm').setValue('654321');
    await wrapper.get('#init-confirm').trigger('keyup.enter');
    await flushPromises();

    expect(wrapper.text()).toContain('两次输入的密码不一致');
    expect(mocks.initSystem).not.toHaveBeenCalled();
  });

  it('提交时系统已初始化：提示并跳登录页', async () => {
    mocks.getInitStatus.mockResolvedValue({ initialized: false });
    mocks.initSystem.mockRejectedValue(new BizError(BizCode.ALREADY_INITIALIZED, '系统已初始化'));
    const wrapper = await mountView(InitView, '/init');

    await wrapper.get('#init-username').setValue('admin001');
    await wrapper.get('#init-realname').setValue('管理员');
    await wrapper.get('#init-password').setValue('123456');
    await wrapper.get('#init-confirm').setValue('123456');
    await wrapper.get('#init-confirm').trigger('keyup.enter');
    await flushPromises();

    expect(mocks.initSystem).toHaveBeenCalledWith({
      adminUsername: 'admin001',
      adminPassword: '123456',
      adminRealName: '管理员',
    });
    expect(router.currentRoute.value.name).toBe('login');
  });
});

describe('登录页', () => {
  it('登录失败：统一提示"账号或密码错误"', async () => {
    mocks.getInitStatus.mockResolvedValue({ initialized: true });
    mocks.login.mockRejectedValue(new BizError(BizCode.LOGIN_FAILED, '账号不存在'));
    const wrapper = await mountView(LoginView, '/login');

    await wrapper.get('#login-username').setValue('zhangsan');
    await wrapper.get('#login-password').setValue('wrong-password');
    await wrapper.get('#login-password').trigger('keyup.enter');
    await flushPromises();

    expect(wrapper.text()).toContain('账号或密码错误');
    expect(wrapper.text()).not.toContain('账号不存在');
  });

  it('登录成功：令牌与权限点写入全局状态并进入工具列表', async () => {
    mocks.getInitStatus.mockResolvedValue({ initialized: true });
    mocks.login.mockResolvedValue({
      token: 'token-value',
      expiresIn: 7200,
      user: {
        userId: '1',
        username: 'zhangsan',
        realName: '张三',
        deptId: '1',
        deptName: '研发部',
        permissions: ['tool:upload', 'tool:download'],
      },
    });
    const wrapper = await mountView(LoginView, '/login');

    await wrapper.get('#login-username').setValue('zhangsan');
    await wrapper.get('#login-password').setValue('123456');
    await wrapper.get('#login-password').trigger('keyup.enter');
    await flushPromises();

    const store = useUserStore();
    expect(store.token).toBe('token-value');
    expect(store.permissions).toEqual(['tool:upload', 'tool:download']);
    expect(store.isLoggedIn).toBe(true);
    // 跳转包含目标页组件的按需加载，等待路由稳定
    // ⚠️ 全量并发运行时（44 个 spec 并行），动态 chunk 解析可能超过 `vi.waitFor` 默认 1s 窗口
    // → 间歇性误判为失败（单文件运行恒绿）。此处**只放宽等待窗口，断言未改动**。
    await vi.waitFor(
      () => {
        expect(router.currentRoute.value.name).toBe('tool-list');
      },
      { timeout: 15000 },
    );
  });

  it('系统未初始化：展示初始化入口', async () => {
    mocks.getInitStatus.mockResolvedValue({ initialized: false });
    const wrapper = await mountView(LoginView, '/login');

    expect(wrapper.text()).toContain('系统尚未初始化');
    expect(wrapper.text()).toContain('前往初始化');
  });
});
