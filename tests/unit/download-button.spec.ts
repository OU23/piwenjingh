/**
 * 测试范围：下载按钮（src/components/DownloadButton.vue）——「取 URL → 导航下载」
 * 测试类型：组件（jsdom）
 * 测试目的：锁死契约 v1.19 §9.5 的新下载口径：**先向后端签发地址，再由浏览器导航下载**
 * 防回归问题：绕开签发接口自行拼地址、把 OSS 直链与本地代理地址分开处理、
 *   签发中重复点击产生多份下载、失败时静默不提示
 * 关键不变量：
 *   - 点「下载」→ 调 `getDownloadUrl(toolId, versionId)` → 用返回的 url 触发 `<a>` 导航（两者同一动作）
 *   - 签发中重复点击**只发起一次**请求
 *   - 401 → 清登录态并跳登录页；403 / 业务码 → 就地提示后端 message；**失败不导航**
 *   - 成功后通知父组件 `done`
 * 边界说明：不覆盖真实浏览器下载器与真实网络；真实 OSS 直链体验见验收 B 组（⛓）
 * 依赖与限制：接口层与 ElMessage 为桩；Element Plus 以插件注册
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import DownloadButton from '@/components/DownloadButton.vue';
import { BizError, ForbiddenError, UnauthorizedError } from '@/api/request';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';

const mocks = vi.hoisted(() => ({
  info: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  getDownloadUrl: vi.fn(),
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { info: mocks.info, error: mocks.error, success: mocks.success, warning: mocks.warning },
  };
});

// 只桩掉下载地址签发（其余 api 层能力本用例不需要）
vi.mock('@/api/tool', () => ({ getDownloadUrl: mocks.getDownloadUrl }));

let pinia: Pinia;
let router: Router;
/** 记录每次导航的真实 href（jsdom 会把相对地址解析成绝对地址） */
let navigated: string[];
/** 桩住 `HTMLAnchorElement.click`，只记录 href 并阻止真实跳转（不改变被测代码的调用方式） */
let anchorClickSpy: { mockRestore: () => void };

async function mountButton(options?: { versionId?: string }): Promise<VueWrapper> {
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'zhangsan',
      realName: '张三',
      deptId: '1',
      deptName: '研发部',
      permissions: ['tool:download'],
    },
  });

  await router.push('/tools');
  await router.isReady();

  const wrapper = mount(DownloadButton, {
    props: {
      toolId: '1001',
      versionId: options?.versionId,
      suggestedName: 'demo.zip',
    },
    global: { plugins: [pinia, router, ElementPlus] },
  });

  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });

  navigated = [];
  // 桩掉导航动作：不真正跳转，只记录 href（不改变被测代码的调用方式）
  anchorClickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    navigated.push(this.href);
  });
});

afterEach(() => {
  anchorClickSpy.mockRestore();
  vi.clearAllMocks();
});

describe('下载按钮（取 URL → 导航下载）', () => {
  it('点「下载」：向接口取地址并导航下载（OSS 直链原样使用）', async () => {
    const signed =
      'https://toolbox-files.oss-cn-hangzhou.aliyuncs.com/tools/abc.zip?Expires=1759212600&Signature=x';
    mocks.getDownloadUrl.mockResolvedValue({ url: signed, expiresIn: 3600, direct: true });

    const wrapper = await mountButton({ versionId: '3005' });
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(mocks.getDownloadUrl).toHaveBeenCalledTimes(1);
    expect(mocks.getDownloadUrl).toHaveBeenCalledWith('1001', '3005');
    // 直链为绝对地址：原样导航到 OSS（字节不过后端）
    expect(navigated).toEqual([signed]);
    expect(mocks.error).not.toHaveBeenCalled();
    // 导航下载由浏览器接管：组件内不再有自定义进度节点
    expect(wrapper.find('.tb-dl__progress').exists()).toBe(false);
  });

  it('本地文件（后端令牌地址）走同一动作：同样导航下载', async () => {
    mocks.getDownloadUrl.mockResolvedValue({
      url: '/api/tools/1001/download?token=tk&versionId=3005',
      expiresIn: 60,
      direct: false,
    });

    const wrapper = await mountButton({ versionId: '3005' });
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(navigated).toHaveLength(1);
    expect(navigated[0]).toContain('/api/tools/1001/download?token=tk');
    expect(mocks.getDownloadUrl).toHaveBeenCalledTimes(1);
  });

  it('签发中重复点击只发起一次请求', async () => {
    let resolveIssue: (value: unknown) => void = () => undefined;
    const pending = new Promise((resolve) => {
      resolveIssue = resolve;
    });
    mocks.getDownloadUrl.mockReturnValue(pending);

    const wrapper = await mountButton();
    await wrapper.get('button').trigger('click');
    await wrapper.get('button').trigger('click');
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(mocks.getDownloadUrl).toHaveBeenCalledTimes(1);
    expect(navigated).toHaveLength(0);

    resolveIssue({ url: '/api/tools/1001/download?token=tk', expiresIn: 60, direct: false });
    await flushPromises();

    expect(navigated).toHaveLength(1);
  });

  it('成功后通知父组件 done（列表/详情据此刷新下载次数）', async () => {
    mocks.getDownloadUrl.mockResolvedValue({
      url: '/api/tools/1001/download?token=tk',
      expiresIn: 60,
      direct: false,
    });

    const wrapper = await mountButton();
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(wrapper.emitted('done')).toHaveLength(1);
  });

  it('未登录（401）：清登录态、跳登录页，且不导航', async () => {
    mocks.getDownloadUrl.mockRejectedValue(new UnauthorizedError('登录状态已失效，请重新登录'));
    // 只断言"发起了跳转"：登录页为懒加载路由，断言最终路由会依赖异步导航完成时机
    const pushSpy = vi.spyOn(router, 'push');

    const wrapper = await mountButton();
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(navigated).toHaveLength(0);
    expect(useUserStore().token).toBe('');
    expect(mocks.error).toHaveBeenCalledWith('登录状态已失效，请重新登录');
    expect(pushSpy).toHaveBeenCalledWith({ name: 'login' });

    pushSpy.mockRestore();
  });

  it('无权限（403）：就地提示，不导航', async () => {
    mocks.getDownloadUrl.mockRejectedValue(new ForbiddenError(''));

    const wrapper = await mountButton();
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(navigated).toHaveLength(0);
    expect(mocks.error).toHaveBeenCalledWith('无下载权限');
    expect(useUserStore().token).toBe('token-value');
  });

  it('对象存储故障（2006）：提示后端原因，不导航（E-02 隔离：仅本次下载失败）', async () => {
    mocks.getDownloadUrl.mockRejectedValue(new BizError(2006, '对象存储暂不可用'));

    const wrapper = await mountButton();
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(navigated).toHaveLength(0);
    expect(mocks.error).toHaveBeenCalledWith('对象存储暂不可用');
    // 不影响登录态：用户仍处于登录状态，其他功能照常
    expect(useUserStore().token).toBe('token-value');
  });
});
