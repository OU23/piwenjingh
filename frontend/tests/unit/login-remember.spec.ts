/**
 * 测试范围：登录页「记住密码」（src/views/login/LoginView.vue + utils/rememberedAccount）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死「进入时预填 → 勾选状态 → 登录成功后按勾选保存 / 清除」
 * 防回归问题：已记住却不预填、未授权即默认保存密码、勾选后不保存
 * 关键不变量：
 *   - 已记住 → 预填账号密码、复选框默认勾选
 *   - 未记住 → 不预填、默认不勾选
 *   - 默认勾选（已记住）时登录成功 → 覆盖保存最新账号密码
 *   - 未勾选（未记住）时登录成功 → 不写入本地存储
 * 边界说明：不覆盖真实接口与跳转后的页面渲染
 * 依赖与限制：接口模块为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus, { ElCheckbox } from 'element-plus';
import { routes } from '@/router';
import LoginView from '@/views/login/LoginView.vue';
import { readRememberedAccount, saveRememberedAccount } from '@/utils/rememberedAccount';

const mocks = vi.hoisted(() => ({ getInitStatus: vi.fn(), login: vi.fn() }));

vi.mock('@/api/system', () => ({ getInitStatus: mocks.getInitStatus }));

vi.mock('@/api/auth', () => ({
  login: mocks.login,
  logout: vi.fn(async () => null),
  getCurrentUser: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listTools: vi.fn(async () => ({ list: [], total: 0, pageNum: 1, pageSize: 20 })),
  toolDownloadUrl: (id: string) => `/api/tools/${id}/download`,
}));

vi.mock('@/api/tag', () => ({ listTags: vi.fn(async () => []) }));

let pinia: Pinia;
let router: Router;

async function mountLogin(): Promise<VueWrapper> {
  await router.push('/login');
  await router.isReady();
  const wrapper = mount(LoginView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

const inputValue = (wrapper: VueWrapper, selector: string): string =>
  (wrapper.get(selector).element as HTMLInputElement).value;

const loginSuccess = () =>
  mocks.login.mockResolvedValue({
    token: 't',
    expiresIn: 7200,
    user: {
      userId: '1',
      username: 'zhangsan',
      realName: '张三',
      deptId: '1',
      deptName: '研发部',
      permissions: [],
    },
  });

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  localStorage.clear();
  mocks.getInitStatus.mockReset();
  mocks.login.mockReset();
  mocks.getInitStatus.mockResolvedValue({ initialized: true });
});

describe('登录页「记住密码」', () => {
  it('已记住：进入时预填账号密码并默认勾选', async () => {
    saveRememberedAccount('zhangsan', '123456');
    const wrapper = await mountLogin();

    expect(inputValue(wrapper, '#login-username')).toBe('zhangsan');
    expect(inputValue(wrapper, '#login-password')).toBe('123456');
    expect(wrapper.findComponent(ElCheckbox).props('modelValue')).toBe(true);
  });

  it('未记住：不预填、默认不勾选', async () => {
    const wrapper = await mountLogin();

    expect(inputValue(wrapper, '#login-username')).toBe('');
    expect(inputValue(wrapper, '#login-password')).toBe('');
    expect(wrapper.findComponent(ElCheckbox).props('modelValue')).toBe(false);
  });

  it('默认勾选（已记住）→ 登录成功覆盖保存最新账号密码', async () => {
    saveRememberedAccount('old', 'oldpwd');
    loginSuccess();
    const wrapper = await mountLogin();

    await wrapper.get('#login-username').setValue('zhangsan');
    await wrapper.get('#login-password').setValue('123456');
    await wrapper.get('#login-password').trigger('keyup.enter');
    await flushPromises();

    expect(readRememberedAccount()).toEqual({ username: 'zhangsan', password: '123456' });
  });

  it('未勾选（未记住）→ 登录成功不写入本地存储', async () => {
    loginSuccess();
    const wrapper = await mountLogin();

    await wrapper.get('#login-username').setValue('zhangsan');
    await wrapper.get('#login-password').setValue('123456');
    await wrapper.get('#login-password').trigger('keyup.enter');
    await flushPromises();

    expect(readRememberedAccount()).toBeNull();
  });
});
