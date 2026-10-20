/**
 * 测试范围：工具编辑页（src/views/tool/ToolEditView.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死 M-25 编辑页的三条硬口径
 *   ① 名称 / 版本号只读（本期不改名）
 *   ② 归属部门走独立接口，且在内容保存**之后**调用
 *   ③ 部门失败时提示"基本信息已保存"，不回滚内容
 * 防回归问题：编辑页放出版本号、把部门塞进 PUT /{id}、部门失败后用户以为全部没保存
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import ToolMetaForm from '@/components/ToolMetaForm.vue';
import type { ToolDetailVO } from '@/api/tool';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import { createEmptyToolMeta, type ToolMetaValues } from '@/utils/toolMetaValidation';
import ToolEditView from '@/views/tool/ToolEditView.vue';

const mocks = vi.hoisted(() => ({
  getToolDetail: vi.fn(),
  updateTool: vi.fn(),
  updateToolDept: vi.fn(),
  listTags: vi.fn(),
  listDepts: vi.fn(),
}));

vi.mock('@/api/tool', async () => {
  const actual = await vi.importActual<typeof import('@/api/tool')>('@/api/tool');
  return {
    ...actual,
    getToolDetail: mocks.getToolDetail,
    updateTool: mocks.updateTool,
    updateToolDept: mocks.updateToolDept,
  };
});

vi.mock('@/api/tag', () => ({ listTags: mocks.listTags }));
vi.mock('@/api/dept', () => ({ listDepts: mocks.listDepts }));

const DETAIL: ToolDetailVO = {
  id: '1001',
  name: '财务对账脚本',
  latestVersion: 'v2.0',
  intro: '旧简介',
  fileExt: 'py',
  scope: 'DEPT',
  tags: [],
  uploaderName: '张三',
  uploaderId: '1',
  deptName: '研发部',
  fileSize: 2048,
  downloadCount: 0,
  updateTime: '2026-10-18 10:00:00',
  toolType: 'FILE',
  linkUrl: null,
  readme: '',
  platform: '',
  dependency: '',
  deptId: '1',
  createTime: '2026-10-01 10:00:00',
  versions: [],
};

const LINK_DETAIL: ToolDetailVO = {
  ...DETAIL,
  id: '1004',
  name: '内部知识库',
  latestVersion: '',
  fileExt: '',
  fileSize: 0,
  toolType: 'LINK',
  linkUrl: 'https://example.com/kb',
};

let pinia: Pinia;
let router: Router;

function values(overrides: Partial<ToolMetaValues> = {}): ToolMetaValues {
  return { ...createEmptyToolMeta(), ...overrides };
}

async function mountEdit(
  detail: ToolDetailVO = DETAIL,
  permissions: string[] = ['tool:assign'],
): Promise<VueWrapper> {
  mocks.getToolDetail.mockResolvedValue(detail);
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'zhangsan',
      realName: '张三',
      deptId: '1',
      deptName: '研发部',
      permissions,
    },
  });

  await router.push(`/tools/${detail.id}/edit`);
  await router.isReady();

  const wrapper = mount(ToolEditView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  mocks.getToolDetail.mockReset();
  mocks.updateTool.mockReset().mockResolvedValue(null);
  mocks.updateToolDept.mockReset().mockResolvedValue(null);
  mocks.listTags.mockReset().mockResolvedValue([]);
  mocks.listDepts.mockReset().mockResolvedValue([
    { id: '1', name: '研发部', status: 1 },
    { id: '2', name: '财务部', status: 1 },
  ]);
});

describe('工具编辑页（M-25）', () => {
  it('名称与版本号只读：不渲染输入框', async () => {
    const wrapper = await mountEdit();

    expect(wrapper.find('#meta-name').exists()).toBe(false);
    expect(wrapper.find('#meta-version').exists()).toBe(false);
    expect(wrapper.text()).toContain('不可修改');

    // 名称用 readonly 输入框展示（与其他字段同样的边框），值必须是工具原名
    const nameInput = wrapper.get('.tb-meta__name-readonly input').element as HTMLInputElement;
    expect(nameInput.readOnly).toBe(true);
    expect(nameInput.value).toBe('财务对账脚本');
  });

  it('保存内容：PUT /{id} 恒传原名，FILE 带版本号与格式', async () => {
    const wrapper = await mountEdit();
    wrapper.findComponent(ToolMetaForm).vm.$emit('submit', values({ intro: '新简介', scope: 'DEPT' }));
    await flushPromises();

    expect(mocks.updateTool).toHaveBeenCalledTimes(1);
    const [id, payload] = mocks.updateTool.mock.calls[0];
    expect(id).toBe('1001');
    expect(payload.name).toBe('财务对账脚本');
    expect(payload.intro).toBe('新简介');
    expect(payload.version).toBe('v2.0');
    expect(payload.fileExt).toBe('py');
    // 部门绝不塞进内容接口
    expect(payload.deptId).toBeUndefined();
  });

  it('部门有变化：先保存内容、再调部门接口', async () => {
    const order: string[] = [];
    mocks.updateTool.mockImplementation(async () => {
      order.push('content');
      return null;
    });
    mocks.updateToolDept.mockImplementation(async () => {
      order.push('dept');
      return null;
    });

    const wrapper = await mountEdit();
    wrapper
      .findComponent(ToolMetaForm)
      .vm.$emit('submit', values({ intro: '新简介', scope: 'DEPT', deptId: '2' }));
    await flushPromises();

    expect(mocks.updateToolDept).toHaveBeenCalledWith('1001', '2');
    expect(order).toEqual(['content', 'dept']);
  });

  it('部门调整失败：提示"基本信息已保存"，不回滚内容', async () => {
    mocks.updateToolDept.mockRejectedValue(new Error('部门不存在或已停用'));

    const wrapper = await mountEdit();
    wrapper
      .findComponent(ToolMetaForm)
      .vm.$emit('submit', values({ intro: '新简介', scope: 'DEPT', deptId: '2' }));
    await flushPromises();

    expect(wrapper.text()).toContain('基本信息已保存');
    expect(wrapper.text()).toContain('部门不存在或已停用');
    // 内容接口已成功，且没有被再次调用来"回滚"
    expect(mocks.updateTool).toHaveBeenCalledTimes(1);
  });

  it('内容保存失败：不再调部门接口', async () => {
    mocks.updateTool.mockRejectedValue(new Error('保存失败'));

    const wrapper = await mountEdit();
    wrapper
      .findComponent(ToolMetaForm)
      .vm.$emit('submit', values({ intro: '新简介', scope: 'DEPT', deptId: '2' }));
    await flushPromises();

    expect(mocks.updateToolDept).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('保存失败');
  });

  it('链接工具编辑：不传 version / fileExt，改的是 linkUrl', async () => {
    const wrapper = await mountEdit(LINK_DETAIL);
    wrapper
      .findComponent(ToolMetaForm)
      .vm.$emit('submit', values({ intro: '新简介', scope: 'DEPT', deptId: '1', linkUrl: 'https://example.com/new' }));
    await flushPromises();

    const [, payload] = mocks.updateTool.mock.calls[0];
    expect(payload.linkUrl).toBe('https://example.com/new');
    expect(payload.version).toBeUndefined();
    expect(payload.fileExt).toBeUndefined();
  });
});
