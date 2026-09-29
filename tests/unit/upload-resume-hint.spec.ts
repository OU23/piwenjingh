/**
 * 测试范围：上传页在"放弃填写"与"上传中刷新"两种情形下的落点（src/views/tool/UploadView.vue
 * + src/stores/uploadDraft.ts）
 * 测试类型：组件（jsdom，含一次"模拟刷新"＝新建 Pinia 重新挂载）
 * 测试目的：锁死两条口径——
 *   1. 用户已放弃的那次填写，**不得**在之后刷新时把页面劫持回"填写信息"并带着上一份工具的信息；
 *   2. 上传中刷新 → 回到**选择文件**步骤，并提示"重新选择同一文件可从断点继续"。
 * 防回归问题：
 *   - 表单「取消」只 `step = 'pick'` 不清草稿 → sessionStorage 里留下旧草稿 → 之后刷新跳到填写信息
 *     （截图为证：刷新后看到的是上一个工具的名称/简介/标签）；
 *   - 上传中刷新 → 无任何记录，用户不知道"其实可以续传"。
 * 关键不变量：
 *   - 「取消」后 sessionStorage 的草稿被清空；再次进入页面停在选择文件步骤，且不出现上一份工具的信息；
 *   - 存在"未完成上传"记录时，进入页面停在选择文件步骤并展示续传提示；
 *   - 上传进度会被写入"未完成上传"记录；开始新上传会清掉旧记录；
 *   - "未完成上传"记录在**正常离开页面**（路由卸载）后不残留。
 * 边界说明：不校验真实断点续传（见 chunk-upload.spec.ts）；File 对象无法序列化，故不做"自动续传"断言。
 * 依赖与限制：上传任务与接口均为桩；Element Plus 以插件注册。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import ElementPlus from 'element-plus';
import ToolMetaForm from '@/components/ToolMetaForm.vue';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import UploadView from '@/views/tool/UploadView.vue';

/** 与 src/stores/uploadDraft.ts 的存储键保持一致（该文件是这两个键的唯一出处） */
const DRAFT_KEY = 'toolbox.upload-draft';
const PENDING_KEY = 'toolbox.upload-pending';

const mocks = vi.hoisted(() => ({
  loadUploadContext: vi.fn(),
  submitTool: vi.fn(),
  run: vi.fn(),
  stop: vi.fn(),
  cancel: vi.fn(),
  pause: vi.fn(),
  resume: vi.fn(),
  createTask: vi.fn(),
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
    readonly totalChunks = 4;
    constructor(options: unknown) {
      mocks.createTask(options);
    }
    run = mocks.run;
    stop = mocks.stop;
    cancel = mocks.cancel;
    pause = mocks.pause;
    resume = mocks.resume;
  }

  return {
    ChunkUploadTask: FakeTask,
    UploadCanceledError: class UploadCanceledError extends Error {},
    createFileIdentifier: async () => 'tbf-testident-1024',
  };
});

/** 创建一套全新的 Pinia 并激活（模拟"刷新页面"：store 会重新从 sessionStorage 读取） */
function freshPinia(): Pinia {
  const pinia = createPinia();
  setActivePinia(pinia);
  return pinia;
}

async function mountUpload(pinia: Pinia): Promise<VueWrapper> {
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

  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push('/upload');
  await router.isReady();

  const wrapper = mount(UploadView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

async function pickFile(wrapper: VueWrapper, file: File): Promise<void> {
  const input = wrapper.get('input[type="file"]');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
  await flushPromises();
}

/** 上一轮"已放弃"的填写草稿：step=meta + 已上传完成 */
function seedAbandonedDraft(): void {
  sessionStorage.setItem(
    DRAFT_KEY,
    JSON.stringify({
      mode: 'create',
      toolName: '',
      step: 'meta',
      uploadResult: { fileKey: 'file-key-old', fileName: '上一个工具.zip', fileSize: 100 },
      meta: { name: '上一个工具' },
    }),
  );
}

function seedPendingUpload(uploadedChunks = 4): void {
  sessionStorage.setItem(
    PENDING_KEY,
    JSON.stringify({ fileName: '财务工具.zip', fileSize: 1024, totalChunks: 10, uploadedChunks }),
  );
}

beforeEach(() => {
  sessionStorage.clear();
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

describe('上传页的落点：放弃填写与上传中刷新', () => {
  it('仍可续填：存在 step=meta 且已上传完成的草稿时，进入页面停在"填写信息"', async () => {
    seedAbandonedDraft();

    const wrapper = await mountUpload(freshPinia());

    expect(wrapper.findComponent(ToolMetaForm).exists()).toBe(true);
    expect(wrapper.text()).toContain('上一个工具');
  });

  it('表单「取消」会清掉草稿：之后刷新回到选择文件，不再被旧草稿劫持', async () => {
    seedAbandonedDraft();
    const wrapper = await mountUpload(freshPinia());
    expect(wrapper.findComponent(ToolMetaForm).exists()).toBe(true);

    wrapper.findComponent(ToolMetaForm).vm.$emit('cancel');
    await flushPromises();

    // 已放弃：草稿必须落盘清空（否则刷新时又会被读回来）
    expect(sessionStorage.getItem(DRAFT_KEY)).toBeNull();
    expect(wrapper.find('input[type="file"]').exists()).toBe(true);

    // 模拟刷新：全新 Pinia 重新挂载，应停在选择文件步骤，且不带上一份工具的信息
    const reloaded = await mountUpload(freshPinia());

    expect(reloaded.findComponent(ToolMetaForm).exists()).toBe(false);
    expect(reloaded.find('input[type="file"]').exists()).toBe(true);
    expect(reloaded.text()).not.toContain('上一个工具');
  });

  it('上传中刷新：回到选择文件步骤，并提示可重新选择同一文件续传', async () => {
    seedPendingUpload();

    const wrapper = await mountUpload(freshPinia());

    // 关键：不跳"填写信息"，而是留在选择文件步骤
    expect(wrapper.findComponent(ToolMetaForm).exists()).toBe(false);
    expect(wrapper.find('input[type="file"]').exists()).toBe(true);

    const text = wrapper.text();
    expect(text).toContain('财务工具.zip');
    expect(text).toContain('4');
    expect(text).toContain('10');
    expect(text).toContain('从断点继续');
  });

  it('上传进度会被记录，供刷新后提示续传', async () => {
    mocks.run.mockReturnValue(new Promise(() => undefined));
    const wrapper = await mountUpload(freshPinia());
    await pickFile(wrapper, new File([new Uint8Array(1024)], 'demo.zip'));

    const options = mocks.createTask.mock.calls[0][0] as { onProgress: (progress: unknown) => void };
    options.onProgress({
      uploadedBytes: 512,
      totalBytes: 1024,
      uploadedChunks: 2,
      totalChunks: 4,
      speed: null,
      remainingMs: null,
    });
    await flushPromises();

    expect(JSON.parse(sessionStorage.getItem(PENDING_KEY) ?? 'null')).toMatchObject({
      fileName: 'demo.zip',
      fileSize: 1024,
      totalChunks: 4,
      uploadedChunks: 2,
    });
  });

  it('开始新上传会清掉上一轮的未完成记录（避免展示过期提示）', async () => {
    seedPendingUpload();
    mocks.run.mockResolvedValue({ fileKey: 'file-key-new', fileName: 'demo.zip', fileSize: 1024 });

    const wrapper = await mountUpload(freshPinia());
    expect(wrapper.text()).toContain('财务工具.zip');

    await pickFile(wrapper, new File([new Uint8Array(1024)], 'demo.zip'));

    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
    expect(wrapper.text()).not.toContain('财务工具.zip');
  });

  it('正常离开页面（路由卸载）后不残留未完成记录', async () => {
    seedPendingUpload();
    const wrapper = await mountUpload(freshPinia());
    expect(wrapper.text()).toContain('财务工具.zip');

    wrapper.unmount();

    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });
});
