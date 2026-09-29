/**
 * 测试范围：用户批量停用 / 批量分配权限（P13 / 需求 9；契约 v1.16  §六）
 *           —— `UserBatchActions.vue` 入口 + `UserView.vue` 多选集成 + `BatchPermissionDialog.vue`
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"多选 → 批量停用 / 批量分配权限"的显隐、剔除自身、覆盖式载荷与防僵尸态
 * 防回归问题：无权限仍渲染入口；提交载荷含当前登录人自己（自停用 / 自赋权）；
 *   权限载荷写成增量（旧权限未被清除）；重置/翻页后按钮不消失（P12 Bug1 同类僵尸态）
 * 关键不变量：
 *   - 多选列存在；未勾选时两个按钮均不渲染
 *   - 「批量停用」需 `user:manage`、「批量分配权限」需 `user:grant`，**互不影响**
 *   - 提交前**剔除当前登录人**（需求 9 + 需求 10）；剔除后为空 → 提示且不发请求
 *   - 权限载荷 = **最终全集（覆盖式）**，非增量
 *   - `fetchList`（重置 / 翻页 / 搜索）后同步清空多选态 → 按钮消失
 * 边界说明：不覆盖真实接口与后端判权；后端"管理员 1005 整体拒绝 / 忽略不存在 ID"见后端测试
 * 依赖与限制：各 api 模块与 `saveBlob` 为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import ElementPlus from 'element-plus';
import BatchPermissionDialog from '@/components/BatchPermissionDialog.vue';
import UserView from '@/views/admin/UserView.vue';
import { useUserStore } from '@/stores/user';

const mocks = vi.hoisted(() => ({
  listDepts: vi.fn(),
  listUsers: vi.fn(),
  listPermissions: vi.fn(),
  getUserPermissions: vi.fn(),
  listUserToolGrants: vi.fn(),
  listTools: vi.fn(),
  saveBlob: vi.fn(),
  batchUpdateUserStatus: vi.fn(),
  batchSaveUserPermissions: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
}));

vi.mock('@/api/dept', () => ({
  listDepts: mocks.listDepts,
  createDept: vi.fn(),
  updateDept: vi.fn(),
  updateDeptStatus: vi.fn(),
  downloadDeptTemplate: vi.fn(),
  exportDepts: vi.fn(),
  importDepts: vi.fn(),
}));

vi.mock('@/api/user', () => ({
  listUsers: mocks.listUsers,
  createUser: vi.fn(),
  updateUser: vi.fn(),
  updateUserStatus: vi.fn(),
  getUserPermissions: mocks.getUserPermissions,
  saveUserPermissions: vi.fn(),
  listUserToolGrants: mocks.listUserToolGrants,
  saveUserToolGrants: vi.fn(),
  downloadUserTemplate: vi.fn(),
  exportUsers: vi.fn(),
  importUsers: vi.fn(),
  batchUpdateUserStatus: mocks.batchUpdateUserStatus,
  batchSaveUserPermissions: mocks.batchSaveUserPermissions,
}));

vi.mock('@/api/permission', () => ({ listPermissions: mocks.listPermissions }));
vi.mock('@/api/tool', () => ({ listTools: mocks.listTools }));
vi.mock('@/api/request', () => ({
  saveBlob: mocks.saveBlob,
  BizError: class BizError extends Error {},
  ForbiddenError: class ForbiddenError extends Error {},
  UnauthorizedError: class UnauthorizedError extends Error {},
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: {
      success: mocks.success,
      error: mocks.error,
      info: vi.fn(),
      warning: mocks.warning,
    },
    ElMessageBox: { confirm: mocks.confirm },
  };
});

/** 当前登录人（userId='1'）——批量提交时必须被剔除 */
const SELF = {
  id: '1',
  username: 'admin',
  realName: '管理员',
  deptId: '1',
  deptName: '研发部',
  status: 1,
  createTime: '2026-09-01 09:00:00',
};
const OTHER = {
  id: '13',
  username: 'zhangsan',
  realName: '张三',
  deptId: '1',
  deptName: '研发部',
  status: 1,
  createTime: '2026-09-01 09:00:00',
};

const DUAL_ADMIN = ['user:manage', 'user:grant'];

let pinia: Pinia;

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

async function mountView(permissions: string[] = DUAL_ADMIN): Promise<VueWrapper> {
  signIn(permissions);
  const wrapper = mount(UserView, { global: { plugins: [pinia, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

/** 模拟表格多选（避开 el-table 在 jsdom 下的交互细节） */
async function select(wrapper: VueWrapper, rows: unknown[]): Promise<void> {
  wrapper.findComponent({ name: 'ElTable' }).vm.$emit('selection-change', rows);
  await flushPromises();
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().includes(text));
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  Object.values(mocks).forEach((mock) => mock.mockReset());

  mocks.listDepts.mockResolvedValue([{ id: '1', name: '研发部', remark: '', status: 1, userCount: 2, createTime: '' }]);
  mocks.listUsers.mockResolvedValue({ list: [SELF, OTHER], total: 2, pageNum: 1, pageSize: 20 });
  mocks.listPermissions.mockResolvedValue([
    { groupCode: 'tool', groupName: '工具', items: [{ code: 'tool:download', name: '下载工具', description: '' }] },
  ]);
  mocks.getUserPermissions.mockResolvedValue([]);
  mocks.listUserToolGrants.mockResolvedValue([]);
  mocks.listTools.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 100 });
  mocks.batchUpdateUserStatus.mockResolvedValue(null);
  mocks.batchSaveUserPermissions.mockResolvedValue(null);
  mocks.confirm.mockResolvedValue('confirm');
});

describe('用户批量操作（P13 / 需求 9）', () => {
  it('A1：表格含多选列；未勾选时两个批量按钮均不渲染', async () => {
    const wrapper = await mountView();

    const hasSelectionColumn = wrapper
      .findAllComponents({ name: 'ElTableColumn' })
      .some((column) => column.props('type') === 'selection');
    expect(hasSelectionColumn).toBe(true);

    expect(buttonByText(wrapper, '批量停用')).toBeUndefined();
    expect(buttonByText(wrapper, '批量分配权限')).toBeUndefined();
  });

  it('A2：按钮按权限显隐且互不影响（manage→停用 / grant→分配权限）', async () => {
    const manageOnly = await mountView(['user:manage']);
    await select(manageOnly, [OTHER]);
    expect(buttonByText(manageOnly, '批量停用')).toBeTruthy();
    expect(buttonByText(manageOnly, '批量分配权限')).toBeUndefined();

    const grantOnly = await mountView(['user:grant']);
    await select(grantOnly, [OTHER]);
    expect(buttonByText(grantOnly, '批量停用')).toBeUndefined();
    expect(buttonByText(grantOnly, '批量分配权限')).toBeTruthy();
  });

  it('A3：勾选 1..N 个账号 → 按钮出现；清空勾选 → 按钮消失', async () => {
    const wrapper = await mountView();

    await select(wrapper, [OTHER, SELF]);
    expect(buttonByText(wrapper, '批量停用')).toBeTruthy();
    expect(buttonByText(wrapper, '批量分配权限')).toBeTruthy();

    await select(wrapper, []);
    expect(buttonByText(wrapper, '批量停用')).toBeUndefined();
    expect(buttonByText(wrapper, '批量分配权限')).toBeUndefined();
  });

  it('A4：点「重置」触发 fetchList 后多选态被清空、按钮消失（防僵尸态）', async () => {
    const wrapper = await mountView();
    await select(wrapper, [OTHER]);
    expect(buttonByText(wrapper, '批量停用')).toBeTruthy();

    await buttonByText(wrapper, '重置')?.trigger('click');
    await flushPromises();

    expect(buttonByText(wrapper, '批量停用')).toBeUndefined();
    expect(buttonByText(wrapper, '批量分配权限')).toBeUndefined();
  });

  it('A5：批量停用剔除当前登录人，载荷不含自己，且提示已排除', async () => {
    const wrapper = await mountView();
    await select(wrapper, [SELF, OTHER]);

    await buttonByText(wrapper, '批量停用')?.trigger('click');
    await flushPromises();

    expect(mocks.confirm).toHaveBeenCalled();
    expect(mocks.batchUpdateUserStatus).toHaveBeenCalledWith({ userIds: ['13'], status: 0 });
    expect(mocks.success).toHaveBeenCalledWith(expect.stringContaining('已排除你自己'));
  });

  it('A5b：只勾选自己 → 不弹确认、不发请求，仅提示', async () => {
    const wrapper = await mountView();
    await select(wrapper, [SELF]);

    await buttonByText(wrapper, '批量停用')?.trigger('click');
    await flushPromises();

    expect(mocks.confirm).not.toHaveBeenCalled();
    expect(mocks.batchUpdateUserStatus).not.toHaveBeenCalled();
    expect(mocks.warning).toHaveBeenCalled();
  });

  it('A6：批量分配权限载荷 = 最终全集（覆盖式，非增量）', async () => {
    const wrapper = await mountView();
    await select(wrapper, [SELF, OTHER]);

    await buttonByText(wrapper, '批量分配权限')?.trigger('click');
    await flushPromises();

    const dialog = wrapper.findComponent(BatchPermissionDialog);
    expect(dialog.props('modelValue')).toBe(true);
    expect(dialog.props('count')).toBe(1); // 已剔除自己

    dialog.vm.$emit('save', { codes: ['tool:download'] });
    await flushPromises();

    expect(mocks.batchSaveUserPermissions).toHaveBeenCalledWith({
      userIds: ['13'],
      permissions: ['tool:download'],
    });
    expect(dialog.props('modelValue')).toBe(false);
  });

  it('A6b：清空权限需二次确认；取消则不发请求', async () => {
    const wrapper = await mountView();
    await select(wrapper, [OTHER]);
    await buttonByText(wrapper, '批量分配权限')?.trigger('click');
    await flushPromises();

    mocks.confirm.mockRejectedValueOnce('cancel');
    // 走弹窗真实的「确定」→ 内部清空二次确认（一个都没勾）
    const dialog = wrapper.findComponent(BatchPermissionDialog);
    await dialog
      .findAll('button')
      .find((item) => item.text().includes('确定'))
      ?.trigger('click');
    await flushPromises();

    expect(mocks.confirm).toHaveBeenCalled();
    expect(mocks.batchSaveUserPermissions).not.toHaveBeenCalled();
  });

  it('A6c：批量接口失败 → 就地提示错误，弹窗保留便于重试', async () => {
    mocks.batchSaveUserPermissions.mockRejectedValue(new Error('不能停用管理员账号'));
    const wrapper = await mountView();
    await select(wrapper, [OTHER]);
    await buttonByText(wrapper, '批量分配权限')?.trigger('click');
    await flushPromises();

    const dialog = wrapper.findComponent(BatchPermissionDialog);
    dialog.vm.$emit('save', { codes: ['tool:download'] });
    await flushPromises();

    expect(dialog.props('modelValue')).toBe(true);
    expect(dialog.props('errorMessage')).toContain('不能停用管理员账号');
  });
});
