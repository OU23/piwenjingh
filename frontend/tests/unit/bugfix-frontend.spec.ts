/**
 * 测试范围：P12 三个前端交互 Bug 修复的回归测试
 *   - Bug 1：工具列表「重置」后「批量删除」按钮不消失（src/views/tool/ToolListView.vue）
 *   - Bug 2：「操作」固定列横向滚动时窜位叠在一起（src/styles/index.css）
 *   - Bug 3：用户管理「分配权限」弹窗内点工具面板就折叠（src/components/PermissionGrantPanel.vue）
 * 测试类型：组件（jsdom）+ CSS 源规则断言
 * 关键不变量：
 *   - Bug1：fetchList 后父级 selectedRows 同步清空，批量删除按钮随选中态正确显隐（无需刷新）
 *   - Bug2：固定列表体单元格有不透明背景规则（防回归误删；真实视觉由人工 B1/B4 + E2E 验证）
 *   - Bug3：PermissionGrantPanel 声明 modelValue 后，根 el-collapse 的 modelValue 不被 grantToolIds 劫持
 * 边界说明：不覆盖真实接口；Bug2 的真实计算样式依赖浏览器渲染，不在 jsdom 内断言
 * 依赖与限制：接口、Element Plus 均为桩
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { readFileSync } from 'node:fs';
import type { ToolVO } from '@/api/tool';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import ToolListView from '@/views/tool/ToolListView.vue';
import PermissionDialog from '@/components/PermissionDialog.vue';

const mocks = vi.hoisted(() => ({
  listTools: vi.fn(),
  listTags: vi.fn(),
  listPermissions: vi.fn(),
  getUserPermissions: vi.fn(),
  listUserToolGrants: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listTools: mocks.listTools,
  toolDownloadUrl: (id: string, versionId?: string) =>
    `/api/tools/${id}/download${versionId ? `?versionId=${versionId}` : ''}`,
}));
vi.mock('@/api/tag', () => ({ listTags: mocks.listTags }));
vi.mock('@/api/permission', () => ({ listPermissions: mocks.listPermissions }));
vi.mock('@/api/user', () => ({
  getUserPermissions: mocks.getUserPermissions,
  listUserToolGrants: mocks.listUserToolGrants,
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
  toolType: 'FILE',
  linkUrl: null,
};

const USER = {
  id: '12',
  username: 'zhangsan',
  realName: '张三',
  deptId: '1',
  deptName: '研发部',
  status: 1,
  createTime: '2026-09-01 09:00:00',
};

const GRANTED_TOOL = { id: '2001', name: '已授权工具', deptName: '财务部', latestVersion: 'v1.2' };
const CANDIDATE_TOOL = {
  id: '2007',
  name: '日志分析器',
  latestVersion: 'v2.1',
  intro: '分析运行日志',
  fileExt: 'zip',
  scope: 'DEPT',
  tags: [],
  uploaderName: '李四',
  deptName: '数据部',
  fileSize: 1024,
  downloadCount: 3,
  updateTime: '2026-09-17 10:00:00',
  toolType: 'FILE',
  linkUrl: null,
};

function page(list: unknown[]): { list: unknown[]; total: number; pageNum: number; pageSize: number } {
  return { list, total: list.length, pageNum: 1, pageSize: 100 };
}

let pinia: Pinia;
let router: Router;

async function mountList(permissions: string[] = []): Promise<VueWrapper> {
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
  await router.push('/tools');
  await router.isReady();
  const wrapper = mount(ToolListView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

async function mountDialog(): Promise<VueWrapper> {
  const wrapper = mount(PermissionDialog, {
    props: { modelValue: true, user: USER },
    global: { plugins: [pinia, ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

async function expandGrantPanel(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('.el-collapse-item__header').trigger('click');
  await flushPromises();
}

function batchDeleteButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find((item) => item.text().includes('批量删除'));
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  Object.values(mocks).forEach((mock) => mock.mockReset());
  mocks.listTags.mockResolvedValue([]);
  mocks.listTools.mockResolvedValue(page([]));
  mocks.listPermissions.mockResolvedValue([
    {
      groupCode: 'custom',
      groupName: '自定义分组',
      items: [
        { code: 'custom:read', name: '只读', description: '仅查看' },
        { code: 'custom:write', name: '可写', description: '可修改' },
      ],
    },
  ]);
  mocks.getUserPermissions.mockResolvedValue(['custom:read']);
  mocks.listUserToolGrants.mockResolvedValue([]);
});

describe('Bug 1：重置后批量删除按钮显隐（A1/A2/A3）', () => {
  it('勾选后点重置：批量删除按钮消失，无需刷新', async () => {
    mocks.listTools.mockResolvedValue(page([TOOL]));
    const wrapper = await mountList(['tool:delete']);

    // 模拟行勾选：触发 selection-change，使父级 selectedRows 有值
    const elTable = wrapper.findComponent({ name: 'ElTable' });
    await elTable.vm.$emit('selection-change', [TOOL]);
    await flushPromises();

    expect(batchDeleteButton(wrapper)).toBeTruthy();

    // 点「重置」触发 fetchList（重建表格并清空 selectedRows）
    const resetBtn = wrapper.findAll('button').find((b) => b.text().includes('重置'));
    await resetBtn?.trigger('click');
    await flushPromises();

    // Bug1 修复前：selectedRows 成僵尸态，按钮仍显示；修复后：按钮消失
    expect(batchDeleteButton(wrapper)).toBeUndefined();
  });

  it('初始未勾选：批量删除按钮本就不可见', async () => {
    mocks.listTools.mockResolvedValue(page([TOOL]));
    const wrapper = await mountList(['tool:delete']);
    expect(batchDeleteButton(wrapper)).toBeUndefined();
  });
});

describe('Bug 2：固定列表体单元格不透明背景（A4/A5，防回归）', () => {
  it('index.css 含 EP2.x 固定列（sticky 单元格）不透明背景规则', () => {
    const css = readFileSync('src/styles/index.css', 'utf-8');
    // EP 2.x 固定列 = position:sticky 单元格，类名 el-table-fixed-column--left/--right
    // （v1 误用旧版 element-ui 的 .el-table__fixed-right，EP2.x DOM 上不存在、规则不命中）
    expect(css).toContain('.el-table__body .el-table-fixed-column--left');
    expect(css).toContain('.el-table__body .el-table-fixed-column--right');
    expect(css).toContain('background-color: var(--tb-panel)');
  });
});

describe('Bug 3：分配权限面板点工具不折叠（A6/A7/A8）', () => {
  it('展开面板加载后：根 el-collapse 的 modelValue 不被 grantToolIds 劫持', async () => {
    mocks.listUserToolGrants.mockResolvedValue([GRANTED_TOOL]);
    mocks.listTools.mockResolvedValue(page([CANDIDATE_TOOL]));

    const wrapper = await mountDialog();
    await expandGrantPanel(wrapper); // 展开 → activePanels = ['grants']
    await flushPromises();

    const collapse = wrapper.findComponent({ name: 'ElCollapse' });
    // 修复后：collapse 的 modelValue 来自面板内部 activePanels（含 'grants'）
    // 未修复：fallthrough 的 grantToolIds 劫持，modelValue 为工具 ID 数组
    expect(collapse.props('modelValue')).toEqual(['grants']);
  });

  it('勾选一个工具后：面板仍展开、collapse 的 modelValue 仍不被劫持', async () => {
    mocks.listUserToolGrants.mockResolvedValue([GRANTED_TOOL]);
    mocks.listTools.mockResolvedValue(page([CANDIDATE_TOOL]));

    const wrapper = await mountDialog();
    await expandGrantPanel(wrapper);
    await flushPromises();

    // 勾选一个候选工具（触发 publish → grantToolIds 变化）
    await wrapper.find('.tb-perm__li').trigger('click');
    await flushPromises();

    const collapse = wrapper.findComponent({ name: 'ElCollapse' });
    expect(collapse.props('modelValue')).toEqual(['grants']);
  });

  it('连续勾选多个工具：面板始终展开、collapse 的 modelValue 始终不被劫持（A8）', async () => {
    mocks.listUserToolGrants.mockResolvedValue([GRANTED_TOOL]);
    mocks.listTools.mockResolvedValue(
      page([
        CANDIDATE_TOOL,
        { ...CANDIDATE_TOOL, id: '2008', name: '日志分析器2' },
      ]),
    );

    const wrapper = await mountDialog();
    await expandGrantPanel(wrapper);
    await flushPromises();

    // 依次勾选两个候选工具，每次勾选都会触发 publish → grantToolIds 变化
    const items = wrapper.findAll('.tb-perm__li');
    await items[0].trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ElCollapse' }).props('modelValue')).toEqual(['grants']);

    await items[1].trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ElCollapse' }).props('modelValue')).toEqual(['grants']);
  });
});
