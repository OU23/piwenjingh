/**
 * 测试范围：上传页「暂停 / 继续」按钮的**接线**（src/views/tool/UploadView.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"点暂停必须真的通知上传任务"——这是本 bug 的病根
 * 防回归问题：暂停按钮只把本地 `paused` ref 翻成 true（界面显示"已暂停"），
 *   却从未调用 `task.pause()` → 调度器仍按 `paused === false` 继续发分片请求，
 *   表现为"提示已暂停、进度条还在涨"。同理"继续"必须调用 `task.resume()`。
 * 关键不变量：
 *   - 点「暂停」→ 上传任务收到 pause()，且按钮变为「继续上传」；
 *   - 点「继续上传」→ 上传任务收到 resume()，且"已暂停"提示消失。
 * 边界说明：不校验真实调度行为（见 upload-pause.spec.ts）；不覆盖大文件实测。
 * 依赖与限制：上传任务为桩（含 pause / resume 探针）；Element Plus 以插件注册。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import UploadProgressPanel from '@/components/UploadProgressPanel.vue';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import UploadView from '@/views/tool/UploadView.vue';

const mocks = vi.hoisted(() => ({
  loadUploadContext: vi.fn(),
  submitTool: vi.fn(),
  run: vi.fn(),
  stop: vi.fn(),
  cancel: vi.fn(),
  pause: vi.fn(),
  resume: vi.fn(),
}));

vi.mock('@/api/uploadContext', () => ({
  loadUploadContext: mocks.loadUploadContext,
}));

vi.mock('@/api/toolSubmit', () => ({
  submitTool: mocks.submitTool,
}));

vi.mock('@/api/chunkUpload', () => {
  class FakeTask {
    readonly identifier = 'identifier-1';
    readonly totalChunks = 3;
    run = mocks.run;
    stop = mocks.stop;
    cancel = mocks.cancel;
    pause = mocks.pause;
    resume = mocks.resume;
    isPaused = (): boolean => false;
  }

  return {
    ChunkUploadTask: FakeTask,
    UploadCanceledError: class UploadCanceledError extends Error {},
    createFileIdentifier: async () => 'tbf-testident-8',
  };
});

let pinia: Pinia;
let router: Router;

/** 停在"上传"步骤：run() 永不结算 → 进度面板可见 */
async function mountAtUploadStep(): Promise<VueWrapper> {
  mocks.run.mockReturnValue(new Promise(() => undefined));

  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'zhangsan',
      realName: '张三',
      deptId: '1',
      deptName: '研发部',
      permissions: ['tool:upload', 'tool:download'],
    },
  });

  await router.push('/upload');
  await router.isReady();

  const wrapper = mount(UploadView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();

  const input = wrapper.get('input[type="file"]');
  Object.defineProperty(input.element, 'files', {
    value: [new File([new Uint8Array(8)], 'demo.zip')],
    configurable: true,
  });
  await input.trigger('change');
  await flushPromises();

  return wrapper;
}

function buttonWithText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((button) => button.text().includes(text));
}

beforeEach(() => {
  sessionStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });

  for (const mock of Object.values(mocks)) {
    mock.mockReset();
  }
  mocks.loadUploadContext.mockResolvedValue({
    maxFileSize: 10 * 1024 * 1024,
    maxFileSizeError: '',
    tags: [],
    tool: null,
  });
});

describe('上传页暂停 / 继续的接线', () => {
  it('点「暂停」→ 真正调用上传任务的 pause()（只改界面状态不算）', async () => {
    const wrapper = await mountAtUploadStep();
    expect(wrapper.findComponent(UploadProgressPanel).exists()).toBe(true);

    const pauseButton = buttonWithText(wrapper, '暂停');
    expect(pauseButton).toBeTruthy();

    await pauseButton!.trigger('click');
    await flushPromises();

    expect(mocks.pause).toHaveBeenCalledTimes(1);
    // 界面同步：出现暂停提示，且按钮变为「继续上传」
    expect(wrapper.text()).toContain('已暂停');
    expect(buttonWithText(wrapper, '继续上传')).toBeTruthy();
  });

  it('点「继续上传」→ 真正调用上传任务的 resume()', async () => {
    const wrapper = await mountAtUploadStep();

    await buttonWithText(wrapper, '暂停')!.trigger('click');
    await flushPromises();

    await buttonWithText(wrapper, '继续上传')!.trigger('click');
    await flushPromises();

    expect(mocks.resume).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).not.toContain('已暂停');
  });
});
