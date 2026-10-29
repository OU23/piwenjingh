/**
 * 测试范围：部门 / 用户管理页的导入导出入口（src/views/admin/DeptView.vue、UserView.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死工具栏集成与页面侧口径（acceptance A10 / A11 与「按钮位置」需求）
 * 防回归问题：按钮没排在「新建」之后、导出漏传当前筛选、导入成功后页面不刷新、
 *   模板下载绕开鉴权通道（未走 api 层封装）
 * 关键不变量：
 *   - 工具栏顺序为「新建 → 下载模板 → 导入 → 导出」（需求方 2026-09-24 明确）
 *   - 导出带上列表当前的 `keyword` / `deptId`
 *   - 导入结束（有成功行）→ 刷新账号/部门列表；**用户页还要刷新部门字典**（导入可自动建部门）
 * 边界说明：不覆盖真实后端；导入对话框自身的行为见 import-dialog.spec.ts
 * 依赖与限制：各 api 模块与 `saveBlob` 为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import ElementPlus from 'element-plus';
import type { ImportResultVO } from '@/api/types';
import { useUserStore } from '@/stores/user';
import DeptView from '@/views/admin/DeptView.vue';
import UserView from '@/views/admin/UserView.vue';
import ImportDialog from '@/components/ImportDialog.vue';

const mocks = vi.hoisted(() => ({
  listDepts: vi.fn(),
  downloadDeptTemplate: vi.fn(),
  exportDepts: vi.fn(),
  importDepts: vi.fn(),
  listUsers: vi.fn(),
  downloadUserTemplate: vi.fn(),
  exportUsers: vi.fn(),
  importUsers: vi.fn(),
  listPermissions: vi.fn(),
  getUserPermissions: vi.fn(),
  listUserToolGrants: vi.fn(),
  listTools: vi.fn(),
  saveBlob: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/dept', () => ({
  listDepts: mocks.listDepts,
  createDept: vi.fn(),
  updateDept: vi.fn(),
  updateDeptStatus: vi.fn(),
  downloadDeptTemplate: mocks.downloadDeptTemplate,
  exportDepts: mocks.exportDepts,
  importDepts: mocks.importDepts,
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
  downloadUserTemplate: mocks.downloadUserTemplate,
  exportUsers: mocks.exportUsers,
  importUsers: mocks.importUsers,
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
    ElMessage: { success: mocks.success, error: mocks.error, info: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: vi.fn() },
  };
});

const DEPT = { id: '1', name: '研发部', remark: '', status: 1, userCount: 2, createTime: '2026-09-01 09:00:00' };
const USER = {
  id: '12',
  username: 'zhangsan',
  realName: '张三',
  deptId: '1',
  deptName: '研发部',
  status: 1,
  createTime: '2026-09-01 09:00:00',
};

const PAYLOAD = { blob: new Blob(['x']), fileName: '导出.xlsx' };

let pinia: Pinia;

function signIn(permissions: string[]): void {
  useUserStore().setLogin({
    token: 'token-value',
    user: { userId: '1', username: 'admin', realName: '管理员', deptId: '1', deptName: '研发部', permissions },
  });
}

async function mountView(component: unknown, permissions: string[]): Promise<VueWrapper> {
  signIn(permissions);
  const wrapper = mount(component as never, { global: { plugins: [pinia, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().includes(text));
}

function toolbarButtons(wrapper: VueWrapper): string[] {
  return wrapper.findAll('.tb-toolbar__ops button').map((item) => item.text().trim());
}

/** 在已打开导入弹窗上完成一次成功导入，并触发关闭 */
async function runSuccessfulImport(wrapper: VueWrapper): Promise<void> {
  const dialog = wrapper.findComponent(ImportDialog);
  const file = new File(['x'], '导入.xlsx');
  const input = dialog.find('input[type="file"]');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
  await flushPromises();

  await dialog.findAll('button').find((item) => item.text().includes('开始导入'))?.trigger('click');
  await flushPromises();

  // 关闭（jsdom 下过渡被 stub，直接派发 close）
  dialog.findComponent({ name: 'ElDialog' }).vm.$emit('close');
  await flushPromises();
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  Object.values(mocks).forEach((mock) => mock.mockReset());

  mocks.listDepts.mockResolvedValue([DEPT]);
  mocks.listUsers.mockResolvedValue({ list: [USER], total: 1, pageNum: 1, pageSize: 20 });
  mocks.downloadDeptTemplate.mockResolvedValue(PAYLOAD);
  mocks.exportDepts.mockResolvedValue(PAYLOAD);
  mocks.downloadUserTemplate.mockResolvedValue(PAYLOAD);
  mocks.exportUsers.mockResolvedValue(PAYLOAD);
  mocks.importDepts.mockResolvedValue({
    total: 1,
    successCount: 1,
    skipCount: 0,
    failCount: 0,
    createdDeptNames: [],
    errors: [],
    errorFileBase64: null,
    errorFileName: null,
  } satisfies ImportResultVO);
  mocks.importUsers.mockResolvedValue({
    total: 1,
    successCount: 1,
    skipCount: 0,
    failCount: 0,
    createdDeptNames: ['新零售部'],
    errors: [],
    errorFileBase64: null,
    errorFileName: null,
  } satisfies ImportResultVO);
});

describe('部门管理页 · 导入导出入口', () => {
  it('工具栏按钮顺序：「新建部门」在最前，其后依次是下载模板 / 导入 / 导出', async () => {
    const wrapper = await mountView(DeptView, ['dept:manage']);

    expect(toolbarButtons(wrapper)).toEqual(['新建部门', '下载模板', '导入', '导出']);
  });

  it('无 dept:manage 权限 → 四个入口都不渲染', async () => {
    const wrapper = await mountView(DeptView, []);

    expect(toolbarButtons(wrapper)).toEqual([]);
  });

  it('「下载模板」→ 调 api 层封装并经 saveBlob 落盘（非直链）', async () => {
    const wrapper = await mountView(DeptView, ['dept:manage']);

    await buttonByText(wrapper, '下载模板')?.trigger('click');
    await flushPromises();

    expect(mocks.downloadDeptTemplate).toHaveBeenCalledTimes(1);
    expect(mocks.saveBlob).toHaveBeenCalledWith(PAYLOAD.blob, PAYLOAD.fileName);
  });

  it('「导出」→ 调部门导出并落盘（部门页无筛选，全量导出）', async () => {
    const wrapper = await mountView(DeptView, ['dept:manage']);

    await buttonByText(wrapper, '导出')?.trigger('click');
    await flushPromises();

    expect(mocks.exportDepts).toHaveBeenCalledWith();
    expect(mocks.saveBlob).toHaveBeenCalledWith(PAYLOAD.blob, PAYLOAD.fileName);
  });

  it('「导入」→ 打开对话框；导入结束（有成功行）→ 重新拉取部门列表（A10）', async () => {
    const wrapper = await mountView(DeptView, ['dept:manage']);
    const callsBefore = mocks.listDepts.mock.calls.length;

    await buttonByText(wrapper, '导入')?.trigger('click');
    await flushPromises();
    expect(wrapper.findComponent(ImportDialog).props('modelValue')).toBe(true);
    expect(wrapper.findComponent(ImportDialog).props('title')).toBe('Excel 导入 · 部门');

    await runSuccessfulImport(wrapper);

    expect(mocks.importDepts).toHaveBeenCalledTimes(1);
    expect(mocks.listDepts.mock.calls.length).toBe(callsBefore + 1);
  });

  it('导出失败 → 就地提示，不抛未捕获异常', async () => {
    mocks.exportDepts.mockRejectedValue(new Error('导出服务不可用'));
    const wrapper = await mountView(DeptView, ['dept:manage']);

    await buttonByText(wrapper, '导出')?.trigger('click');
    await flushPromises();

    expect(mocks.error).toHaveBeenCalledWith('导出服务不可用');
    expect(mocks.saveBlob).not.toHaveBeenCalled();
  });
});

describe('用户管理页 · 导入导出入口', () => {
  it('工具栏按钮顺序：「新建账号」在最前，其后依次是下载模板 / 导入 / 导出', async () => {
    const wrapper = await mountView(UserView, ['user:manage']);

    expect(toolbarButtons(wrapper)).toEqual(['新建账号', '下载模板', '导入', '导出']);
  });

  it('「下载模板」→ 调用户模板接口并落盘', async () => {
    const wrapper = await mountView(UserView, ['user:manage']);

    await buttonByText(wrapper, '下载模板')?.trigger('click');
    await flushPromises();

    expect(mocks.downloadUserTemplate).toHaveBeenCalledTimes(1);
    expect(mocks.saveBlob).toHaveBeenCalledWith(PAYLOAD.blob, PAYLOAD.fileName);
  });

  it('「导出」带上列表当前的关键词筛选（A11）', async () => {
    const wrapper = await mountView(UserView, ['user:manage']);

    await wrapper.find('.tb-filter__item.is-wide input').setValue('张');
    await buttonByText(wrapper, '导出')?.trigger('click');
    await flushPromises();

    expect(mocks.exportUsers).toHaveBeenCalledWith({ keyword: '张', deptId: undefined });
  });

  it('无筛选时导出：两个条件都为 undefined（后端按全量导出）', async () => {
    const wrapper = await mountView(UserView, ['user:manage']);

    await buttonByText(wrapper, '导出')?.trigger('click');
    await flushPromises();

    expect(mocks.exportUsers).toHaveBeenCalledWith({ keyword: undefined, deptId: undefined });
  });

  it('导入结束 → 刷新账号列表**并且刷新部门字典**（导入可自动新建部门）', async () => {
    const wrapper = await mountView(UserView, ['user:manage']);
    const usersBefore = mocks.listUsers.mock.calls.length;
    const deptsBefore = mocks.listDepts.mock.calls.length;

    await buttonByText(wrapper, '导入')?.trigger('click');
    await flushPromises();
    expect(wrapper.findComponent(ImportDialog).props('title')).toBe('Excel 导入 · 用户');

    await runSuccessfulImport(wrapper);

    expect(mocks.importUsers).toHaveBeenCalledTimes(1);
    expect(mocks.listUsers.mock.calls.length).toBe(usersBefore + 1);
    expect(mocks.listDepts.mock.calls.length).toBe(deptsBefore + 1);
  });

  it('重复点击「导出」不会并发发起多次请求', async () => {
    let release: (value: typeof PAYLOAD) => void = () => {};
    mocks.exportUsers.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );

    const wrapper = await mountView(UserView, ['user:manage']);
    await buttonByText(wrapper, '导出')?.trigger('click');
    await flushPromises();
    await buttonByText(wrapper, '导出')?.trigger('click');
    await flushPromises();

    expect(mocks.exportUsers).toHaveBeenCalledTimes(1);

    release(PAYLOAD);
    await flushPromises();
  });
});
