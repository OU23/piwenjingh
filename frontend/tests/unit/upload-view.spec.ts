/**
 * 测试范围：上传页编排（src/views/tool/UploadView.vue）——超限拦截、提交时序、卸载清理、连点保护
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"上传完成前不能提交""超上限零请求""离开不残留请求"这三条（P3 验收 A6 / A7 / A10 / C7）
 * 防回归问题：超限仍发起上传、未拿到文件标识就能提交、卸载后请求继续、连点提交产生两条工具
 * 关键不变量：
 *   - 文件超过上限 → 不创建上传任务（零请求）
 *   - 只有合并拿到文件标识后才进入"填写信息"步骤
 *   - 组件卸载 → 调用停止（清理在途请求）
 *   - 提交中再次提交不生效
 * 边界说明：不覆盖真实分片传输（另见 chunk-upload.spec.ts）与 P5 大文件实测
 * 依赖与限制：上传任务与接口均为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import ToolMetaForm from '@/components/ToolMetaForm.vue';
import UploadFilePicker from '@/components/UploadFilePicker.vue';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import UploadView from '@/views/tool/UploadView.vue';

const mocks = vi.hoisted(() => ({
  loadUploadContext: vi.fn(),
  submitTool: vi.fn(),
  run: vi.fn(),
  stop: vi.fn(),
  cancel: vi.fn(),
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
    readonly totalChunks = 3;
    readonly options: unknown;

    constructor(options: unknown) {
      this.options = options;
      mocks.createTask(options);
    }

    run = mocks.run;
    stop = mocks.stop;
    cancel = mocks.cancel;
  }

  return {
    ChunkUploadTask: FakeTask,
    UploadCanceledError: class UploadCanceledError extends Error {},
    // 标识由调用方异步算出后传入（补充说明 N-05）：桩里给固定值即可
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

/** 通过真实的文件输入触发选择（走组件内的预校验） */
async function pickFile(wrapper: VueWrapper, file: File): Promise<void> {
  const input = wrapper.get('input[type="file"]');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
  await flushPromises();
}

beforeEach(() => {
  // 上传草稿落在 sessionStorage（侧边栏切走/刷新可续填）：用例间必须清空，否则上个用例的草稿
  // 会让下个用例直接进入"填写信息"步骤，取不到文件输入框（夹具对齐，非断言放宽）
  sessionStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  mocks.loadUploadContext.mockReset();
  mocks.submitTool.mockReset();
  mocks.run.mockReset();
  mocks.stop.mockReset();
  mocks.cancel.mockReset();
  mocks.createTask.mockReset();
  mocks.loadUploadContext.mockResolvedValue({
    maxFileSize: 10 * 1024 * 1024,
    maxFileSizeError: '',
    tags: [],
    tool: null,
  });
});

describe('上传页编排', () => {
  it('超过单文件上限：前端拦截，不创建上传任务（验收 A6）', async () => {
    const wrapper = await mountView();
    const tooLarge = new File([new Uint8Array(11 * 1024 * 1024)], 'huge.zip');

    await pickFile(wrapper, tooLarge);

    expect(mocks.createTask).not.toHaveBeenCalled();
    expect(mocks.run).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('超过上限');
    // 仍停留在选择文件步骤
    expect(wrapper.findComponent(UploadFilePicker).exists()).toBe(true);
  });

  it('空文件：同样被拦截（验收 C1）', async () => {
    const wrapper = await mountView();

    await pickFile(wrapper, new File([], 'empty.zip'));

    expect(mocks.run).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('文件为空');
  });

  it('提交时序：拿到文件标识后才出现填写信息步骤（验收 A7）', async () => {
    let finishRun: (value: { fileKey: string; fileName: string; fileSize: number }) => void = () => undefined;
    mocks.run.mockReturnValue(
      new Promise((resolve) => {
        finishRun = resolve;
      }),
    );

    const wrapper = await mountView();
    await pickFile(wrapper, new File([new Uint8Array(1024)], 'demo.zip'));

    // 上传中：不应出现元数据表单（也就不可能提交）
    expect(wrapper.text()).toContain('分片');
    expect(wrapper.findComponent(ToolMetaForm).exists()).toBe(false);

    finishRun({ fileKey: 'file-key-1', fileName: 'demo.zip', fileSize: 1024 });
    await flushPromises();

    expect(wrapper.findComponent(ToolMetaForm).exists()).toBe(true);
    expect(mocks.submitTool).not.toHaveBeenCalled();
  });

  it('卸载时停止上传，避免残留请求（验收 A10）', async () => {
    mocks.run.mockReturnValue(new Promise(() => undefined));

    const wrapper = await mountView();
    await pickFile(wrapper, new File([new Uint8Array(1024)], 'demo.zip'));

    wrapper.unmount();

    expect(mocks.stop).toHaveBeenCalled();
  });

  it('提交中重复提交只生效一次（验收 C7）', async () => {
    mocks.run.mockResolvedValue({ fileKey: 'file-key-1', fileName: 'demo.zip', fileSize: 1024 });
    mocks.submitTool.mockReturnValue(new Promise(() => undefined));

    const wrapper = await mountView();
    await pickFile(wrapper, new File([new Uint8Array(1024)], 'demo.zip'));

    const form = wrapper.findComponent(ToolMetaForm);
    const values = {
      name: '工具',
      version: 'v1.0',
      intro: '简介',
      readme: '',
      fileExt: 'zip',
      platform: '',
      dependency: '',
      tagIds: [],
      scope: 'DEPT',
    };

    form.vm.$emit('submit', values);
    form.vm.$emit('submit', values);
    await flushPromises();

    expect(mocks.submitTool).toHaveBeenCalledTimes(1);
  });
});
