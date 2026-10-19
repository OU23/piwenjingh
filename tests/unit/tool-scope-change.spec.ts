/**
 * 测试范围：工具「可见范围」变更（ToolTable 入口 + ToolListView 处理）—— P14 / 需求 11
 *          ＋ 入口判权口径修正（P15 / 契约 v1.15）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"列表操作列变更工具可见范围"的权限显隐、二次确认、全量覆盖式提交载荷与失败语义
 * 防回归问题：无权限仍渲染入口；提交只带 scope 导致 readme/platform/dependency 被清空；
 *   收窄不经确认直接生效；失败后本地显示态被乐观改写
 * 关键不变量：
 *   - 入口显隐（**契约 v1.15**）：仅**同时**持 `user:manage` + `user:grant` 才渲染；
 *     仅 `tool:upload`（P14 旧口径）、或只持其中一个，均**不渲染**
 *   - 提交走 `PUT /api/tools/{id}`（updateTool），载荷含 name/version/intro/fileExt/scope 及
 *     readme/platform/dependency/tagIds（从详情取全量，防全量覆盖式提交丢字段）
 *   - 收窄（PUBLIC→DEPT）与放开（DEPT→PUBLIC）均需确认；取消则不发请求
 *   - 失败仅提示，不刷新、不改本地态；不触碰授权端点
 * 边界说明：不覆盖真实接口与后端判权；编辑判权权威在后端（`ToolWriteService.requireEditable`），
 *   前端仅做入口显隐（R5）
 * 依赖与限制：工具 / 标签接口为桩；Element Plus 以插件注册（ElMessage / ElMessageBox 打桩）
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import type { ToolDetailVO, ToolVO } from '@/api/tool';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import ToolListView from '@/views/tool/ToolListView.vue';

const mocks = vi.hoisted(() => ({
  listTools: vi.fn(),
  listTags: vi.fn(),
  getToolDetail: vi.fn(),
  updateTool: vi.fn(),
  saveToolGrants: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  confirm: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listTools: mocks.listTools,
  getToolDetail: mocks.getToolDetail,
  updateTool: mocks.updateTool,
  saveToolGrants: mocks.saveToolGrants,
  batchDeleteTools: vi.fn(),
  deleteTool: vi.fn(),
  toolDownloadUrl: (id: string, versionId?: string) =>
    `/api/tools/${id}/download${versionId ? `?versionId=${versionId}` : ''}`,
}));

vi.mock('@/api/tag', () => ({
  listTags: mocks.listTags,
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { success: mocks.success, error: mocks.error, info: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: mocks.confirm },
  };
});

const TOOL: ToolVO = {
  id: '1001',
  name: '数据清洗小工具',
  latestVersion: 'v1.2',
  intro: '批量清洗 CSV 的工具',
  fileExt: 'zip',
  scope: 'DEPT',
  tags: [{ id: '1', name: '数据处理' }],
  uploaderName: '张三',
  uploaderId: '9',
  deptName: '研发部',
  fileSize: 2048,
  downloadCount: 7,
  updateTime: '2026-09-17 10:00:00',
};

/** 详情比列表多 readme / platform / dependency —— 正是"必须先取详情"的原因 */
const DETAIL: ToolDetailVO = {
  ...TOOL,
  readme: '先解压再运行 run.bat',
  platform: 'Windows',
  dependency: 'Python 3.10',
  uploaderId: '1',
  deptId: '1',
  createTime: '2026-09-01 09:00:00',
  versions: [],
};

function page(list: ToolVO[]): { list: ToolVO[]; total: number; pageNum: number; pageSize: number } {
  return { list, total: list.length, pageNum: 1, pageSize: 20 };
}

let pinia: Pinia;
let router: Router;

function signIn(permissions: string[]): void {
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'admin',
      realName: '管理员',
      deptId: '1',
      deptName: '研发部',
      permissions,
    },
  });
}

/** 契约 v1.15：入口可见 = 同时持 user:manage + user:grant */
const DUAL_ADMIN = ['user:manage', 'user:grant'];

async function mountList(permissions: string[] = DUAL_ADMIN): Promise<VueWrapper> {
  signIn(permissions);
  await router.push('/tools');
  await router.isReady();

  const wrapper = mount(ToolListView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

/** 「可见范围」入口 = 下拉触发器（link 按钮） */
function scopeEntry(wrapper: VueWrapper) {
  return wrapper.findAll('button').filter((item) => item.text().includes('可见范围'));
}

/** 通过 ElDropdown 的 command 事件模拟"选中某一档"，避开 popper 在 jsdom 中的可见性问题 */
async function chooseScope(wrapper: VueWrapper, scope: 'DEPT' | 'PUBLIC'): Promise<void> {
  wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', scope);
  await flushPromises();
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  Object.values(mocks).forEach((mock) => mock.mockReset());

  mocks.listTags.mockResolvedValue([]);
  mocks.listTools.mockResolvedValue(page([TOOL]));
  mocks.getToolDetail.mockResolvedValue(DETAIL);
  mocks.updateTool.mockResolvedValue(null);
  mocks.saveToolGrants.mockResolvedValue(null);
  mocks.confirm.mockResolvedValue('confirm');
});

describe('工具可见范围变更（P14 / 需求 11）', () => {
  it('无双权限（此处无任何权限）→ 不渲染「可见范围」入口（A1）', async () => {
    const wrapper = await mountList(['tool:download']);
    expect(scopeEntry(wrapper)).toHaveLength(0);
  });

  it('同时持 user:manage + user:grant → 入口渲染，且列表展示当前范围（A2）', async () => {
    const wrapper = await mountList(DUAL_ADMIN);
    expect(scopeEntry(wrapper)).toHaveLength(1);
    expect(wrapper.text()).toContain('本部门可见');
  });

  it('仅持 tool:upload（P14 旧口径，契约 v1.15 已作废）→ 不渲染（P15-B2）', async () => {
    const wrapper = await mountList(['tool:upload']);
    expect(scopeEntry(wrapper)).toHaveLength(0);
  });

  it('只持 user:manage 或只持 user:grant（AND 语义，缺一不可）→ 均不渲染（P15-B3）', async () => {
    expect(scopeEntry(await mountList(['user:manage']))).toHaveLength(0);
    expect(scopeEntry(await mountList(['user:grant']))).toHaveLength(0);
  });

  it('放开（DEPT→PUBLIC）：载荷含全部字段，含从详情取的 readme/platform/dependency/tagIds（A3/A5）', async () => {
    const wrapper = await mountList(DUAL_ADMIN);
    const callsBefore = mocks.listTools.mock.calls.length;

    await chooseScope(wrapper, 'PUBLIC');

    expect(mocks.confirm).toHaveBeenCalled();
    expect(mocks.getToolDetail).toHaveBeenCalledWith('1001');
    expect(mocks.updateTool).toHaveBeenCalledWith('1001', {
      name: '数据清洗小工具',
      version: 'v1.2',
      intro: '批量清洗 CSV 的工具',
      readme: '先解压再运行 run.bat',
      fileExt: 'zip',
      platform: 'Windows',
      dependency: 'Python 3.10',
      tagIds: ['1'],
      scope: 'PUBLIC',
    });
    // 成功后刷新列表（A5）
    expect(mocks.listTools.mock.calls.length).toBe(callsBefore + 1);
    expect(mocks.success).toHaveBeenCalled();
  });

  it('收窄（PUBLIC→DEPT）取消确认 → 不发请求、不改本地态（A4）', async () => {
    mocks.listTools.mockResolvedValue(page([{ ...TOOL, scope: 'PUBLIC' }]));
    mocks.confirm.mockRejectedValue('cancel');

    const wrapper = await mountList(DUAL_ADMIN);
    await chooseScope(wrapper, 'DEPT');

    expect(mocks.confirm).toHaveBeenCalled();
    expect(mocks.getToolDetail).not.toHaveBeenCalled();
    expect(mocks.updateTool).not.toHaveBeenCalled();
    // 本地仍显示"全员可见"，未被乐观改写
    expect(wrapper.text()).toContain('全员可见');
  });

  it('收窄（PUBLIC→DEPT）确认后按 scope=DEPT 提交（A4）', async () => {
    mocks.listTools.mockResolvedValue(page([{ ...TOOL, scope: 'PUBLIC' }]));
    const wrapper = await mountList(DUAL_ADMIN);

    await chooseScope(wrapper, 'DEPT');

    expect(mocks.updateTool).toHaveBeenCalledWith('1001', expect.objectContaining({ scope: 'DEPT' }));
  });

  it('接口失败 → 提示错误、不刷新、不改本地态（A6）', async () => {
    mocks.updateTool.mockRejectedValue(new Error('可见范围变更失败'));
    const wrapper = await mountList(DUAL_ADMIN);
    const callsBefore = mocks.listTools.mock.calls.length;

    await chooseScope(wrapper, 'PUBLIC');

    expect(mocks.error).toHaveBeenCalled();
    expect(mocks.listTools.mock.calls.length).toBe(callsBefore);
    expect(wrapper.text()).toContain('本部门可见');
  });

  it('变更 scope 不触碰授权端点（A7）', async () => {
    const wrapper = await mountList(DUAL_ADMIN);
    await chooseScope(wrapper, 'PUBLIC');
    expect(mocks.saveToolGrants).not.toHaveBeenCalled();
  });

  it('选中的就是当前档 → 不发请求（幂等）', async () => {
    const wrapper = await mountList(DUAL_ADMIN);
    await chooseScope(wrapper, 'DEPT'); // TOOL.scope 已是 DEPT
    expect(mocks.confirm).not.toHaveBeenCalled();
    expect(mocks.updateTool).not.toHaveBeenCalled();
  });
});
