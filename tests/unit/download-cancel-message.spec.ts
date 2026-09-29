/**
 * 测试范围：下载按钮"用户主动取消"的提示（src/components/DownloadButton.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：点击"取消"时给可读文案「下载已取消」，不暴露英文的 AbortError（如 "The user aborted a request."）
 * 防回归问题：fetch 被 abort 抛 AbortError 时直接把英文 message 弹给用户
 * 关键不变量：
 *   - 非安全上下文（provider.supported=false）走整包降级路径
 *   - 运行中再次点击 = 取消 → 提示「下载已取消」
 * 边界说明：不覆盖安全上下文的流式续传分支（另有 download.spec.ts / download-button.spec.ts）
 * 依赖与限制：fetch 为桩；Element Plus 的 ElMessage 被打桩
 */
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import ElementPlus from 'element-plus';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import DownloadButton from '@/components/DownloadButton.vue';

const mocks = vi.hoisted(() => ({
  info: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { info: mocks.info, error: mocks.error, success: mocks.success, warning: mocks.warning },
  };
});

describe('下载取消提示', () => {
  it('运行中点「取消」：提示「下载已取消」而非英文 AbortError', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);

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

    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push('/tools');
    await router.isReady();

    // 永远不 resolve 的 fetch：abort 时抛出英文 AbortError（模拟真实浏览器）
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: string, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => {
              reject(new DOMException('The user aborted a request.', 'AbortError'));
            });
          }),
      ),
    );

    const wrapper = mount(DownloadButton, {
      props: {
        toolId: '5',
        suggestedName: 'tool.zip',
        // supported=false → 非安全上下文，走整包降级路径
        provider: { supported: false, pick: vi.fn(async () => null) },
      },
      global: { plugins: [pinia, router, ElementPlus] },
    });

    await wrapper.get('button').trigger('click');
    await flushPromises();

    // 运行中再次点击 = 取消
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(mocks.info).toHaveBeenCalledWith('下载已取消');
    expect(mocks.error).not.toHaveBeenCalled();

    vi.unstubAllGlobals();
  });
});
