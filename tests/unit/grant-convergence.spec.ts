/**
 * 测试范围：P11 定向授权收敛的前端三处收敛（T6 按钮显隐 / T7 工具侧候选 / T8 用户侧候选 / T9 部门透传）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"PUBLIC 工具不出现授权入口、候选列表剔除无意义配对、目标用户部门透传进面板"
 * 防回归问题：PUBLIC 工具仍渲染授权按钮、候选里混进同部门人员/PUBLIC 工具/同部门工具、
 *   目标用户部门未透传导致面板无法剔除同部门工具、动生产代码来让测试绿
 * 关键不变量：
 *   - T6：`scope==='PUBLIC'` 工具在列表/详情均**不渲染**"授权"按钮（A1）
 *   - T7：工具侧候选**不含**工具所属部门人员（A10）
 *   - T8：用户侧候选**不含** PUBLIC 工具、**不含**目标同部门工具（A9）
 *   - T9：目标用户 `deptName` 经 `PermissionDialog` 透传进 `PermissionGrantPanel`（T9）
 * 边界说明：不覆盖真实接口与后端校验；后端收敛一致性由后端测试保证
 * 依赖与限制：接口模块均为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import type { ToolDetailVO, ToolVO } from '@/api/tool';
import type { ToolBriefVO } from '@/api/types';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import ToolTable from '@/components/ToolTable.vue';
import ToolDetailView from '@/views/tool/ToolDetailView.vue';
import ToolGrantDialog from '@/components/ToolGrantDialog.vue';
import PermissionGrantPanel from '@/components/PermissionGrantPanel.vue';
import PermissionDialog from '@/components/PermissionDialog.vue';

const mocks = vi.hoisted(() => ({
  listToolGrants: vi.fn(),
  saveToolGrants: vi.fn(),
  listTools: vi.fn(),
  listUsers: vi.fn(),
  listUserToolGrants: vi.fn(),
  listPermissions: vi.fn(),
  getUserPermissions: vi.fn(),
  getToolDetail: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listToolGrants: mocks.listToolGrants,
  saveToolGrants: mocks.saveToolGrants,
  listTools: mocks.listTools,
  getToolDetail: mocks.getToolDetail,
  toolDownloadUrl: (id: string, versionId?: string) =>
    `/api/tools/${id}/download${versionId ? `?versionId=${versionId}` : ''}`,
}));

vi.mock('@/api/user', () => ({
  listUsers: mocks.listUsers,
  listUserToolGrants: mocks.listUserToolGrants,
  getUserPermissions: mocks.getUserPermissions,
}));

vi.mock('@/api/permission', () => ({
  listPermissions: mocks.listPermissions,
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { success: mocks.success, error: mocks.error, info: vi.fn(), warning: vi.fn() },
  };
});

/** 构造列表项（含 scope）；PUBLIC / DEPT 两种 */
function makeTool(id: string, name: string, scope: string, deptName: string): ToolVO {
  return {
    id,
    name,
    latestVersion: 'v1.0',
    intro: 'x',
    fileExt: 'zip',
    scope,
    tags: [],
    uploaderName: '张三',
    uploaderId: '9',
    deptName,
    fileSize: 1024,
    downloadCount: 0,
    updateTime: '2026-09-17 10:00:00',
    toolType: 'FILE',
    linkUrl: null,
  };
}

/** 构造详情（继承列表项全部字段） */
function makeDetail(id: string, name: string, scope: string, deptName: string): ToolDetailVO {
  return {
    ...makeTool(id, name, scope, deptName),
    readme: null,
    platform: null,
    dependency: null,
    uploaderId: '1',
    deptId: '1',
    createTime: '2026-09-01 09:00:00',
    versions: [],
  };
}

function makeUser(id: string, username: string, realName: string, deptName: string) {
  return { id, username, realName, deptId: '1', deptName, status: 1, createTime: '2026-09-01 09:00:00' };
}

function page(list: unknown[]): { list: unknown[]; total: number; pageNum: number; pageSize: number } {
  return { list, total: list.length, pageNum: 1, pageSize: 100 };
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

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  Object.values(mocks).forEach((mock) => mock.mockReset());

  mocks.listToolGrants.mockResolvedValue([]);
  mocks.listUsers.mockResolvedValue(page([]));
  mocks.listTools.mockResolvedValue(page([]));
  mocks.listUserToolGrants.mockResolvedValue([]);
  mocks.listPermissions.mockResolvedValue([]);
  mocks.getUserPermissions.mockResolvedValue([]);
  mocks.getToolDetail.mockResolvedValue(makeDetail('1001', '工具A', 'DEPT', '研发部'));
  mocks.saveToolGrants.mockResolvedValue(null);
});

describe('T6 · PUBLIC 工具隐藏授权按钮（A1）', () => {
  it('列表页：DEPT 工具渲染授权按钮，PUBLIC 工具不渲染', async () => {
    signIn(['user:grant']);
    const list = [
      makeTool('1001', '工具A', 'DEPT', '研发部'),
      makeTool('1002', '工具B', 'PUBLIC', '研发部'),
    ];
    const wrapper = mount(ToolTable, {
      props: { list },
      global: { plugins: [pinia, router, ElementPlus] },
    });
    await flushPromises();

    const grantButtons = wrapper.findAll('button').filter((item) => item.text().includes('授权'));
    // 只有 DEPT 工具那一行有按钮
    expect(grantButtons).toHaveLength(1);
  });

  it('列表页：无 user:grant 权限时不渲染任何授权按钮', async () => {
    signIn([]);
    const list = [makeTool('1001', '工具A', 'DEPT', '研发部')];
    const wrapper = mount(ToolTable, {
      props: { list },
      global: { plugins: [pinia, router, ElementPlus] },
    });
    await flushPromises();

    const grantButtons = wrapper.findAll('button').filter((item) => item.text().includes('授权'));
    expect(grantButtons).toHaveLength(0);
  });

  it('详情页：PUBLIC 工具不渲染授权按钮，DEPT 工具渲染', async () => {
    signIn(['user:grant']);

    // PUBLIC 工具
    mocks.getToolDetail.mockResolvedValue(makeDetail('1002', '工具B', 'PUBLIC', '研发部'));
    await router.push({ name: 'tool-detail', params: { id: '1002' } });
    await router.isReady();
    const publicWrapper = mount(ToolDetailView, { global: { plugins: [pinia, router, ElementPlus] } });
    await flushPromises();
    expect(
      publicWrapper.findAll('button').filter((item) => item.text().includes('授权')),
    ).toHaveLength(0);

    // DEPT 工具
    mocks.getToolDetail.mockResolvedValue(makeDetail('1001', '工具A', 'DEPT', '研发部'));
    await router.push({ name: 'tool-detail', params: { id: '1001' } });
    await flushPromises();
    const deptWrapper = mount(ToolDetailView, { global: { plugins: [pinia, router, ElementPlus] } });
    await flushPromises();
    expect(
      deptWrapper.findAll('button').filter((item) => item.text().includes('授权')),
    ).toHaveLength(1);
  });
});

describe('T7 · 工具侧候选排除同部门人员（A10）', () => {
  it('候选用户剔除"工具所属部门人员"，保留跨部门人员', async () => {
    const tool: ToolBriefVO = { id: '1001', name: '工具A', deptName: '研发部', latestVersion: 'v1.0', scope: 'DEPT' };
    mocks.listUsers.mockResolvedValue(
      page([
        makeUser('18', 'lisi', '李四', '研发部'), // 同部门 → 剔除
        makeUser('30', 'zhaoliu', '赵六', '市场部'), // 跨部门 → 保留
      ]),
    );

    const wrapper = mount(ToolGrantDialog, {
      props: { modelValue: true, tool },
      global: { plugins: [pinia, ElementPlus] },
    });
    await flushPromises();

    const rows = wrapper.findAll('.tb-grant__li');
    const text = rows.map((row) => row.text()).join('|');
    expect(text).toContain('赵六');
    expect(text).not.toContain('李四');
    expect(rows).toHaveLength(1);
  });

  it('已授权（同部门）用户仍置顶保留，不被候选过滤误删', async () => {
    const tool: ToolBriefVO = { id: '1001', name: '工具A', deptName: '研发部', latestVersion: 'v1.0', scope: 'DEPT' };
    // 已授权里有一个同部门人员（历史脏数据回显），候选里也有
    const sameDept = { id: '18', username: 'lisi', realName: '李四', deptName: '研发部' };
    mocks.listToolGrants.mockResolvedValue([sameDept]);
    mocks.listUsers.mockResolvedValue(page([sameDept, makeUser('30', 'zhaoliu', '赵六', '市场部')]));

    const wrapper = mount(ToolGrantDialog, {
      props: { modelValue: true, tool },
      global: { plugins: [pinia, ElementPlus] },
    });
    await flushPromises();

    // 已授权置顶保留（不受候选过滤影响），候选不再出现同部门李四
    const rows = wrapper.findAll('.tb-grant__li');
    expect(rows).toHaveLength(2); // 已授权李四(置顶) + 候选赵六
    expect(rows[0]?.classes()).toContain('is-on');
  });
});

describe('T8 · 用户侧候选排除 PUBLIC + 同部门工具（A9）', () => {
  it('候选工具剔除 PUBLIC 与"目标用户同部门工具"，保留跨部门工具', async () => {
    mocks.listTools.mockResolvedValue(
      page([
        makeTool('1001', '工具A', 'PUBLIC', '研发部'), // PUBLIC → 剔除
        makeTool('1002', '工具B', 'DEPT', '研发部'), // 与目标用户同部门 → 剔除
        makeTool('1003', '工具C', 'DEPT', '市场部'), // 跨部门 → 保留
      ]),
    );

    const wrapper = mount(PermissionGrantPanel, {
      props: { userId: '5', userDeptName: '研发部' },
      global: { plugins: [pinia, ElementPlus] },
    });
    // 展开折叠面板触发加载
    await wrapper.find('.el-collapse-item__header').trigger('click');
    await flushPromises();

    const rows = wrapper.findAll('.tb-perm__li');
    const text = rows.map((row) => row.text()).join('|');
    expect(text).toContain('工具C');
    expect(text).not.toContain('工具A');
    expect(text).not.toContain('工具B');
    expect(rows).toHaveLength(1);
  });

  it('未透传部门名时，仅按 PUBLIC 过滤（不误伤同部门）', async () => {
    mocks.listTools.mockResolvedValue(
      page([
        makeTool('1001', '工具A', 'PUBLIC', '研发部'), // PUBLIC → 剔除
        makeTool('1002', '工具B', 'DEPT', '研发部'), // 无部门对比 → 保留
      ]),
    );

    const wrapper = mount(PermissionGrantPanel, {
      props: { userId: '5', userDeptName: '' },
      global: { plugins: [pinia, ElementPlus] },
    });
    await wrapper.find('.el-collapse-item__header').trigger('click');
    await flushPromises();

    const rows = wrapper.findAll('.tb-perm__li');
    const text = rows.map((row) => row.text()).join('|');
    expect(text).toContain('工具B');
    expect(text).not.toContain('工具A');
    expect(rows).toHaveLength(1);
  });
});

describe('T9 · 目标用户部门透传进面板', () => {
  it('PermissionDialog 把选中用户的 deptName 透传给 PermissionGrantPanel', async () => {
    const user = {
      id: '5',
      username: 'wangwu',
      realName: '王五',
      deptId: '2',
      deptName: '市场部',
      status: 1,
      permissions: [],
      createTime: '2026-09-01 09:00:00',
    };
    const wrapper = mount(PermissionDialog, {
      props: { modelValue: true, user },
      global: { plugins: [pinia, ElementPlus] },
    });
    await flushPromises();

    const panel = wrapper.findComponent(PermissionGrantPanel);
    expect(panel.exists()).toBe(true);
    expect(panel.props('userDeptName')).toBe('市场部');
  });
});
