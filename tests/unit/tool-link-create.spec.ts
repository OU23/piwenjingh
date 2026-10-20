/**
 * 测试范围：创建链接工具（src/views/tool/ToolLinkCreate.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死 M-25 的链接创建口径 —— URL 协议白名单、不产生版本、部门下拉仅 tool:assign 可见
 * 防回归问题：`javascript:` 链接被提交、链接工具带上 version/fileExt、无权限者也看到部门选择
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import ToolMetaForm from '@/components/ToolMetaForm.vue';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import { createEmptyToolMeta, type ToolMetaValues } from '@/utils/toolMetaValidation';
import ToolLinkCreate from '@/views/tool/ToolLinkCreate.vue';

const mocks = vi.hoisted(() => ({
  createLinkTool: vi.fn(),
  listTags: vi.fn(),
  listDepts: vi.fn(),
}));

vi.mock('@/api/tool', async () => {
  const actual = await vi.importActual<typeof import('@/api/tool')>('@/api/tool');
  return { ...actual, createLinkTool: mocks.createLinkTool };
});

vi.mock('@/api/tag', () => ({ listTags: mocks.listTags }));
vi.mock('@/api/dept', () => ({ listDepts: mocks.listDepts }));

let pinia: Pinia;
let router: Router;

function values(overrides: Partial<ToolMetaValues> = {}): ToolMetaValues {
  return {
    ...createEmptyToolMeta(),
    name: '内部知识库',
    intro: '研发文档入口',
    scope: 'DEPT',
    ...overrides,
  };
}

async function mountLink(permissions: string[] = []): Promise<VueWrapper> {
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

  await router.push('/upload');
  await router.isReady();

  const wrapper = mount(ToolLinkCreate, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  mocks.createLinkTool.mockReset().mockResolvedValue({ toolId: '2001' });
  mocks.listTags.mockReset().mockResolvedValue([]);
  mocks.listDepts.mockReset().mockResolvedValue([
    { id: '1', name: '研发部', status: 1 },
    { id: '2', name: '财务部', status: 1 },
  ]);
});

describe('创建链接工具（M-25）', () => {
  it('非法协议（javascript:）→ 阻止提交', async () => {
    const wrapper = await mountLink();
    wrapper
      .findComponent(ToolMetaForm)
      .vm.$emit('submit', values({ linkUrl: 'javascript:alert(1)' }));
    await flushPromises();

    expect(mocks.createLinkTool).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('http');
  });

  it('空链接 → 阻止提交', async () => {
    const wrapper = await mountLink();
    wrapper.findComponent(ToolMetaForm).vm.$emit('submit', values({ linkUrl: '   ' }));
    await flushPromises();

    expect(mocks.createLinkTool).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请输入链接地址');
  });

  it('合法链接 → 调 createLinkTool，参数不含 version / fileExt', async () => {
    const wrapper = await mountLink();
    wrapper
      .findComponent(ToolMetaForm)
      .vm.$emit('submit', values({ linkUrl: 'https://example.com/kb', deptId: '1' }));
    await flushPromises();

    expect(mocks.createLinkTool).toHaveBeenCalledTimes(1);
    const payload = mocks.createLinkTool.mock.calls[0][0];
    expect(payload.name).toBe('内部知识库');
    expect(payload.linkUrl).toBe('https://example.com/kb');
    expect(payload.scope).toBe('DEPT');
    expect(payload.deptId).toBe('1');
    expect('version' in payload).toBe(false);
    expect('fileExt' in payload).toBe(false);
  });

  it('无 tool:assign → 不渲染「归属部门」', async () => {
    const wrapper = await mountLink(['tool:upload']);

    expect(wrapper.text()).not.toContain('归属部门');
    expect(mocks.listDepts).not.toHaveBeenCalled();
  });

  it('有 tool:assign → 渲染「归属部门」并加载启用部门', async () => {
    const wrapper = await mountLink(['tool:upload', 'tool:assign']);

    expect(wrapper.text()).toContain('归属部门');
    expect(mocks.listDepts).toHaveBeenCalledWith({ status: 1 });
  });
});
