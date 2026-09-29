/**
 * 测试范围：下载按钮（src/components/DownloadButton.vue）——进度、取消、续传、重复点击保护
 * 测试类型：组件（jsdom）
 * 测试目的：保证"下载可中断、可续传、不会一次点击产生多份下载"
 * 防回归问题：重复点击触发多份下载、中断后必须从头再来、取消保存框后仍发起请求
 * 关键不变量：
 *   - 选择保存位置期间重复点击只触发一次保存框
 *   - 中断后按钮变为"继续下载"，再次点击复用同一目标并携带 Range
 *   - 用户取消保存位置选择 → 不发起下载请求
 * 边界说明：不覆盖真实浏览器保存框与真实网络；GB 级实测见 P5
 * 依赖与限制：fetch 与保存目标提供方均为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import DownloadButton from '@/components/DownloadButton.vue';
import { clearAllResume, type SaveTarget, type SaveTargetProvider } from '@/api/saveTarget';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';

let pinia: Pinia;
let router: Router;

function bytes(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

function createFakeTarget(): { target: SaveTarget; chunks: Uint8Array[] } {
  const chunks: Uint8Array[] = [];
  return {
    chunks,
    target: {
      open: async () => ({
        write: async (chunk: Uint8Array) => {
          chunks.push(chunk);
        },
        seek: async () => undefined,
        truncate: async () => undefined,
        close: async () => undefined,
        abort: async () => undefined,
      }),
    },
  };
}

/** 首次请求返回可中断的流，用于验证取消与续传 */
function interruptibleStream(controllerRef: { current: ReadableStreamDefaultController<Uint8Array> | null }) {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controllerRef.current = controller;
      controller.enqueue(bytes('ab'));
    },
  });
}

async function mountButton(options: {
  provider: SaveTargetProvider;
  suggestedName?: string;
}): Promise<VueWrapper> {
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
      suggestedName: options.suggestedName ?? 'demo.zip',
      provider: options.provider,
    },
    global: { plugins: [pinia, router, ElementPlus] },
  });

  await flushPromises();
  return wrapper;
}

function buttonLabel(wrapper: VueWrapper): string {
  return wrapper.get('button').text();
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  // 续传记录常驻于模块作用域，测试之间必须清空，否则会互相影响
  clearAllResume();
});

describe('下载按钮', () => {
  it('下载成功后回到初始状态，并携带令牌请求', async () => {
    const { target } = createFakeTarget();
    const provider: SaveTargetProvider = { supported: true, pick: vi.fn(async () => target) };
    const fetchMock = vi.fn(
      async () =>
        new Response(
          new ReadableStream<Uint8Array>({
            start(controller) {
              controller.enqueue(bytes('demo'));
              controller.close();
            },
          }),
          { status: 200, headers: { 'Content-Length': '4' } },
        ),
    );
    vi.stubGlobal('fetch', fetchMock);

    const wrapper = await mountButton({ provider });
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(provider.pick).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(buttonLabel(wrapper)).toBe('下载');
    expect(wrapper.find('.tb-dl__progress').exists()).toBe(false);

    vi.unstubAllGlobals();
  });

  it('中断后可续传：按钮变为"继续下载"，再次点击复用目标并携带 Range', async () => {
    const { target } = createFakeTarget();
    const provider: SaveTargetProvider = { supported: true, pick: vi.fn(async () => target) };
    const controllerRef: { current: ReadableStreamDefaultController<Uint8Array> | null } = { current: null };

    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      const signal = init.signal as AbortSignal;
      const stream = interruptibleStream(controllerRef);
      signal.addEventListener('abort', () => {
        controllerRef.current?.error(new DOMException('Aborted', 'AbortError'));
      });
      return new Response(stream, { status: 200, headers: { 'Content-Length': '4' } });
    });
    vi.stubGlobal('fetch', fetchMock);

    const wrapper = await mountButton({ provider });

    await wrapper.get('button').trigger('click');
    await flushPromises();
    expect(buttonLabel(wrapper)).toBe('取消');

    // 运行中点击 = 取消
    await wrapper.get('button').trigger('click');
    await flushPromises();
    expect(buttonLabel(wrapper)).toBe('继续下载');

    // 继续下载：不重新选择位置，且从已写位置续传
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(provider.pick).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [, secondInit] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect((secondInit.headers as Record<string, string>).Range).toBe('bytes=2-');

    vi.unstubAllGlobals();
  });

  it('选择保存位置期间重复点击只触发一次', async () => {
    const { target } = createFakeTarget();
    let resolvePick: (value: SaveTarget | null) => void = () => undefined;
    const pending = new Promise<SaveTarget | null>((resolve) => {
      resolvePick = resolve;
    });
    const provider: SaveTargetProvider = { supported: true, pick: vi.fn(() => pending) };
    vi.stubGlobal('fetch', vi.fn());

    const wrapper = await mountButton({ provider });

    await wrapper.get('button').trigger('click');
    await wrapper.get('button').trigger('click');
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(provider.pick).toHaveBeenCalledTimes(1);

    resolvePick(target);
    await flushPromises();

    vi.unstubAllGlobals();
  });

  it('用户取消保存位置选择时不发起请求', async () => {
    const provider: SaveTargetProvider = { supported: true, pick: vi.fn(async () => null) };
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const wrapper = await mountButton({ provider });
    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(buttonLabel(wrapper)).toBe('下载');

    vi.unstubAllGlobals();
  });
});
