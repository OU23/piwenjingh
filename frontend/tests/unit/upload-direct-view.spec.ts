/**
 * 测试范围：上传页按模式分流（src/views/tool/UploadView.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"进入上传页先问模式、据此二选一创建任务"（M-23 验收 A20）
 * 防回归问题：不看模式一律走原链路、直传模式下仍构造分片任务、分片大小未来自后端
 * 关键不变量：
 *   - `mode=direct` → 构造 DirectUploadTask（**不**构造 ChunkUploadTask）
 *   - `mode=server` → 构造 ChunkUploadTask，且 chunkSize 取后端 partSize
 *   - 页面展示只读的分片大小与当前链路（不再提供输入框）
 * 边界说明：不覆盖真实 OSS 网络行为；任务本身为桩
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import UploadView from '@/views/tool/UploadView.vue';

const PART_SIZE = 20 * 1024 * 1024;

const mocks = vi.hoisted(() => ({
  loadUploadContext: vi.fn(),
  directCreate: vi.fn(),
  directRun: vi.fn(),
  chunkCreate: vi.fn(),
  chunkRun: vi.fn(),
}));

vi.mock('@/api/uploadContext', () => ({ loadUploadContext: mocks.loadUploadContext }));

vi.mock('@/api/directUpload', () => {
  class FakeDirectTask {
    readonly identifier = 'identifier-direct';
    readonly totalChunks = 3;

    constructor(options: unknown) {
      mocks.directCreate(options);
    }

    run = mocks.directRun;
    stop = vi.fn();
    pause = vi.fn();
    resume = vi.fn();
    cancel = vi.fn();
    isPaused = () => false;
  }

  return { DirectUploadTask: FakeDirectTask };
});

vi.mock('@/api/chunkUpload', () => {
  class FakeChunkTask {
    readonly identifier = 'identifier-server';
    readonly totalChunks = 3;

    constructor(options: unknown) {
      mocks.chunkCreate(options);
    }

    run = mocks.chunkRun;
    stop = vi.fn();
    pause = vi.fn();
    resume = vi.fn();
    cancel = vi.fn();
    isPaused = () => false;
  }

  return {
    ChunkUploadTask: FakeChunkTask,
    UploadCanceledError: class UploadCanceledError extends Error {},
    createFileIdentifier: async () => 'tbf-testident-10',
  };
});

let pinia: Pinia;
let router: Router;

async function mountView(): Promise<VueWrapper> {
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'zhangsan',
      realName: '张三',
      deptId: '1',
      deptName: '研发部',
      permissions: ['tool:upload'],
    },
  });

  await router.push('/upload');
  await router.isReady();

  const wrapper = mount(UploadView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

async function pickFile(wrapper: VueWrapper): Promise<void> {
  const input = wrapper.get('input[type="file"]');
  const file = new File([new Uint8Array(1024)], 'demo.zip');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
  await flushPromises();
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });

  mocks.loadUploadContext.mockReset();
  mocks.directCreate.mockReset();
  mocks.directRun.mockReset();
  mocks.chunkCreate.mockReset();
  mocks.chunkRun.mockReset();

  // 任务既不结算也不报错：停在"上传中"，仅用于断言"选了哪条链路"
  mocks.directRun.mockReturnValue(new Promise(() => undefined));
  mocks.chunkRun.mockReturnValue(new Promise(() => undefined));
});

describe('上传页按模式分流（A20）', () => {
  it('mode=direct → 构造直传任务，且展示只读分片大小', async () => {
    mocks.loadUploadContext.mockResolvedValue({
      mode: 'direct',
      partSize: PART_SIZE,
      maxFileSize: 10 * 1024 * 1024,
      maxFileSizeError: '',
      tags: [],
      tagsError: '',
      tool: null,
    });

    const wrapper = await mountView();
    expect(wrapper.text()).toContain('分片大小');
    expect(wrapper.text()).toContain('20.0 MB');
    expect(wrapper.text()).toContain('浏览器直传对象存储');

    await pickFile(wrapper);

    expect(mocks.directCreate).toHaveBeenCalledTimes(1);
    expect(mocks.chunkCreate).not.toHaveBeenCalled();
    expect(mocks.directCreate.mock.calls[0][0]).toMatchObject({ partSize: PART_SIZE });
  });

  it('mode=server → 构造原分片任务，chunkSize 取后端 partSize', async () => {
    mocks.loadUploadContext.mockResolvedValue({
      mode: 'server',
      partSize: PART_SIZE,
      maxFileSize: 10 * 1024 * 1024,
      maxFileSizeError: '',
      tags: [],
      tagsError: '',
      tool: null,
    });

    const wrapper = await mountView();
    expect(wrapper.text()).toContain('经服务器上传');

    await pickFile(wrapper);

    expect(mocks.chunkCreate).toHaveBeenCalledTimes(1);
    expect(mocks.directCreate).not.toHaveBeenCalled();
    expect(mocks.chunkCreate.mock.calls[0][0]).toMatchObject({ chunkSize: PART_SIZE });
  });
});
