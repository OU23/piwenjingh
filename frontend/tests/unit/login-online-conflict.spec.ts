/**
 * 测试范围：登录页「账号已在其他设备登录」二次确认（M-18 / 契约 §3.1，P7-T3）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死 1008 的确认口径 —— 有取消 / 取消不发请求 / 强制登录带 force
 * 防回归问题：1008 被当普通失败直接提示、取消后仍发请求、第二次未带 force 导致死循环
 * 关键不变量：
 *   - `1008` → 弹确认框且**含取消按钮**
 *   - 取消 → **不发起任何请求**
 *   - 确定 → 第二次请求体含 `force: true`，且**沿用已输入的账号密码**
 *   - `1006` / 网络失败 → 普通提示，**不弹**确认框
 * 边界说明：不覆盖真实接口；跳转后的页面渲染由对应页面测试覆盖
 * 依赖与限制：`api/auth`、`api/system` 为桩；`ElMessageBox` 以 spy 替换
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus, { ElMessageBox } from 'element-plus';
import { BizError } from '@/api/request';
import { BizCode } from '@/api/types';
import { routes } from '@/router';
import LoginView from '@/views/login/LoginView.vue';

const mocks = vi.hoisted(() => ({
  getInitStatus: vi.fn(),
  login: vi.fn(),
  listTools: vi.fn(),
}));

vi.mock('@/api/system', () => ({
  getInitStatus: mocks.getInitStatus,
  initSystem: vi.fn(),
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

const LOGIN_OK = {
  token: 'token-value',
  expiresIn: 43200,
  user: {
    userId: '1',
    username: 'zhangsan',
    realName: '张三',
    deptId: '1',
    deptName: '研发部',
    permissions: [],
  },
};

let pinia: Pinia;
let router: Router;

/** 替换「是否强制登录」确认框：默认点确定；`cancel` 表示用户取消 */
function stubConfirm(behavior: 'confirm' | 'cancel' = 'confirm') {
  const spy = vi.spyOn(ElMessageBox, 'confirm');
  if (behavior === 'cancel') {
    spy.mockRejectedValue('cancel');
  } else {
    spy.mockResolvedValue('confirm' as never);
  }
  return spy;
}

async function mountLogin(): Promise<VueWrapper> {
  await router.push('/login');
  await router.isReady();
  const wrapper = mount(LoginView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('#login-username').setValue('zhangsan');
  await wrapper.get('#login-password').setValue('123456');
  await wrapper.get('#login-password').trigger('keyup.enter');
  await flushPromises();
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  mocks.getInitStatus.mockReset();
  mocks.getInitStatus.mockResolvedValue({ initialized: true });
  mocks.login.mockReset();
  mocks.listTools.mockReset();
  mocks.listTools.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
});

describe('业务码常量', () => {
  it('ONLINE_CONFLICT 为 1008（验收 A4）', () => {
    expect(BizCode.ONLINE_CONFLICT).toBe(1008);
  });
});

describe('登录页 · 账号已在线（1008）', () => {
  it('返回 1008 → 弹确认框且含「取消」（验收 A1）', async () => {
    mocks.login.mockRejectedValueOnce(new BizError(BizCode.ONLINE_CONFLICT, '该账号已在其他设备登录'));
    const confirmSpy = stubConfirm();
    const wrapper = await mountLogin();

    await submit(wrapper);

    expect(confirmSpy).toHaveBeenCalledTimes(1);
    const options = confirmSpy.mock.calls[0]?.[2] as Record<string, unknown>;
    expect(options.confirmButtonText).toBe('强制登录');
    expect(options.cancelButtonText).toBe('取消');
    expect(options.type).toBe('warning');
  });

  it('点「取消」→ 不发起任何请求（验收 A2）', async () => {
    mocks.login.mockRejectedValueOnce(new BizError(BizCode.ONLINE_CONFLICT, '已在线'));
    const confirmSpy = stubConfirm('cancel');
    const wrapper = await mountLogin();

    await submit(wrapper);

    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(mocks.login).toHaveBeenCalledTimes(1);
    // 首次提交**不携带 force**（缺省即 false，契约 §3.1）
    expect(mocks.login).toHaveBeenCalledWith({ username: 'zhangsan', password: '123456' });
  });

  it('点「强制登录」→ 第二次请求带 force: true 且沿用已输入账号密码（验收 A3）', async () => {
    mocks.login
      .mockRejectedValueOnce(new BizError(BizCode.ONLINE_CONFLICT, '已在线'))
      .mockResolvedValueOnce(LOGIN_OK);
    stubConfirm();
    const wrapper = await mountLogin();

    await submit(wrapper);

    expect(mocks.login).toHaveBeenCalledTimes(2);
    expect(mocks.login.mock.calls[1]?.[0]).toEqual({
      username: 'zhangsan',
      password: '123456',
      force: true,
    });
  });

  it('强制登录成功 → 登录态写入并进入工具列表', async () => {
    mocks.login
      .mockRejectedValueOnce(new BizError(BizCode.ONLINE_CONFLICT, '已在线'))
      .mockResolvedValueOnce(LOGIN_OK);
    stubConfirm();
    const wrapper = await mountLogin();

    await submit(wrapper);

    await vi.waitFor(() => {
      expect(router.currentRoute.value.name).toBe('tool-list');
    });
  });

  it('强制登录仍失败（如密码刚被改）→ 走普通失败提示且不残留 loading（验收 C3）', async () => {
    mocks.login
      .mockRejectedValueOnce(new BizError(BizCode.ONLINE_CONFLICT, '已在线'))
      .mockRejectedValueOnce(new BizError(BizCode.LOGIN_FAILED, '账号或密码错误'));
    stubConfirm();
    const wrapper = await mountLogin();

    await submit(wrapper);

    expect(wrapper.text()).toContain('账号或密码错误');
    expect(wrapper.get('.tb-login__submit').classes()).not.toContain('is-loading');
  });

  it('1006（密码错）→ 直接提示，不弹确认框（验收 C1）', async () => {
    mocks.login.mockRejectedValueOnce(new BizError(BizCode.LOGIN_FAILED, '账号或密码错误'));
    const confirmSpy = stubConfirm();
    const wrapper = await mountLogin();

    await submit(wrapper);

    expect(confirmSpy).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('账号或密码错误');
  });

  it('网络失败 → 普通提示，不按 1008 处理（验收 C2）', async () => {
    mocks.login.mockRejectedValueOnce(new Error('无法连接服务器，请确认后端服务已启动'));
    const confirmSpy = stubConfirm();
    const wrapper = await mountLogin();

    await submit(wrapper);

    expect(confirmSpy).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('无法连接服务器');
  });

  it('取消后再次提交 → 仍能走到确认框（状态未被污染，验收 C11）', async () => {
    mocks.login.mockRejectedValue(new BizError(BizCode.ONLINE_CONFLICT, '已在线'));
    const confirmSpy = stubConfirm('cancel');
    const wrapper = await mountLogin();

    await submit(wrapper);
    await submit(wrapper);

    expect(mocks.login).toHaveBeenCalledTimes(2);
    expect(confirmSpy).toHaveBeenCalledTimes(2);
  });

  it('提交进行中重复触发 → 不重复发起请求（验收 C9）', async () => {
    stubConfirm();
    let release: (value: unknown) => void = () => undefined;
    mocks.login.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    const wrapper = await mountLogin();

    await wrapper.get('#login-username').setValue('zhangsan');
    await wrapper.get('#login-password').setValue('123456');
    await wrapper.get('#login-password').trigger('keyup.enter');
    await wrapper.get('#login-password').trigger('keyup.enter');
    await flushPromises();

    expect(mocks.login).toHaveBeenCalledTimes(1);

    release(LOGIN_OK);
    await flushPromises();
  });
});
