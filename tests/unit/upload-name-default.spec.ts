/**
 * 测试范围：上传页「文件名 → 工具名称默认值」（T13 / 验收 A34）
 * 测试类型：纯函数 + 组件（jsdom）
 * 目的：名称默认值可改、只在新建模式预填、追加版本不被覆盖、不影响任何校验
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import UploadView from '@/views/tool/UploadView.vue';
import { TOOL_META_LIMITS, nameFromFileName } from '@/utils/toolMetaValidation';

const mocks = vi.hoisted(() => ({
  loadUploadContext: vi.fn(),
  run: vi.fn(),
  stop: vi.fn(),
  cancel: vi.fn(),
  createTask: vi.fn(),
}));

vi.mock('@/api/uploadContext', () => ({ loadUploadContext: mocks.loadUploadContext }));
vi.mock('@/api/toolSubmit', () => ({ submitTool: vi.fn() }));
vi.mock('@/api/chunkUpload', () => {
  class FakeTask {
    run = mocks.run;
    stop = mocks.stop;
    cancel = mocks.cancel;
    constructor(options: unknown) {
      mocks.createTask(options);
    }
  }
  return {
    ChunkUploadTask: FakeTask,
    UploadCanceledError: class UploadCanceledError extends Error {},
    createFileIdentifier: async () => 'tbf-testident-10',
  };
});

let pinia: Pinia;
let router: Router;

async function mountView(path = '/upload'): Promise<VueWrapper> {
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
  await router.push(path);
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

beforeEach(() => {
  sessionStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  mocks.loadUploadContext.mockReset();
  mocks.run.mockReset();
  mocks.stop.mockReset();
  mocks.cancel.mockReset();
  mocks.createTask.mockReset();
  mocks.loadUploadContext.mockResolvedValue({
    mode: 'server',
    partSize: 5 * 1024 * 1024,
    maxFileSize: 10 * 1024 * 1024,
    maxFileSizeError: '',
    tags: [],
    tool: null,
  });
});

describe('nameFromFileName（纯函数）', () => {
  it('去掉最后一个扩展名', () => {
    expect(nameFromFileName('数据清洗小工具.zip')).toBe('数据清洗小工具');
  });

  it('双后缀只剥最后一层（简单规则，可预期）', () => {
    expect(nameFromFileName('tool-v1.2.tar.gz')).toBe('tool-v1.2.tar');
  });

  it('无扩展名保持原样', () => {
    expect(nameFromFileName('mytool')).toBe('mytool');
  });

  it('点开头的隐藏文件：去后缀为空 → 回退原名', () => {
    expect(nameFromFileName('.gitignore')).toBe('.gitignore');
  });

  it('超长截断到名称上限', () => {
    const long = `${'a'.repeat(150)}.zip`;
    expect(nameFromFileName(long)).toHaveLength(TOOL_META_LIMITS.name);
  });

  it('空串 / 纯空白 → 空串', () => {
    expect(nameFromFileName('')).toBe('');
    expect(nameFromFileName('   ')).toBe('');
  });
});

describe('上传页名称预填（A34）', () => {
  it('新建模式：上传完成后名称框 = 去后缀的文件名，且可编辑', async () => {
    mocks.run.mockResolvedValue({ fileKey: 'file-key-1', fileName: '数据清洗小工具.zip', fileSize: 1024 });

    const wrapper = await mountView();
    await pickFile(wrapper, new File([new Uint8Array(1024)], '数据清洗小工具.zip'));

    const input = wrapper.get('#meta-name').element as HTMLInputElement;
    expect(input.value).toBe('数据清洗小工具');
    expect(input.readOnly).toBe(false);
  });

  it('追加版本模式：名称保持工具原名，不被文件名覆盖', async () => {
    mocks.loadUploadContext.mockResolvedValue({
      mode: 'server',
      partSize: 5 * 1024 * 1024,
      maxFileSize: 10 * 1024 * 1024,
      maxFileSizeError: '',
      tags: [],
      tool: {
        name: '原工具名',
        fileExt: 'zip',
        platform: 'Windows',
        dependency: '无',
        intro: '简介',
        scope: 'DEPT',
        tagIds: [],
      },
    });
    mocks.run.mockResolvedValue({ fileKey: 'file-key-2', fileName: '完全不同的名字.zip', fileSize: 1024 });

    const wrapper = await mountView('/upload?toolId=1001');
    await pickFile(wrapper, new File([new Uint8Array(1024)], '完全不同的名字.zip'));

    // 版本模式下名称只读展示，且应为工具原名（展开合并保留了 metaInitial.name）
    expect(wrapper.get('.tb-meta__readonly').text()).toBe('原工具名');
  });
});
