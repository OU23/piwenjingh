/**
 * 测试范围：用户管理页的「分配权限」保存编排（src/views/admin/UserView.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死跨部门授权的**两接口串行提交**与**新建账号默认权限收窄**（P6 验收 A8 / A10）
 * 防回归问题：两个接口并发提交（权限没存上、授权却变了）、第一个失败仍继续提交授权、
 *   授权面板从未展开却把已有授权清空、新账号又默认拿到上传权限
 * 关键不变量：
 *   - 保存顺序固定为 `PUT /permissions` → `PUT /tool-grants`，两者都成功才算保存成功
 *   - 第一个请求失败 → **不发**第二个请求，弹窗保留
 *   - 折叠面板未展开过（`toolIds === null`）→ **跳过**授权提交，不误清空
 *   - 清空权限点 + 二次确认取消 → **一个请求都不发**
 * 边界说明：不覆盖真实接口；接口失败后的可重试性由人工验收覆盖
 * 依赖与限制：权限字典 / 用户列表 / 部门字典 / 工具列表均为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import ElementPlus from 'element-plus';
import { DEFAULT_NEW_USER_PERMISSIONS } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import UserFormDialog from '@/components/UserFormDialog.vue';
import UserView from '@/views/admin/UserView.vue';

const mocks = vi.hoisted(() => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  updateUserStatus: vi.fn(),
  getUserPermissions: vi.fn(),
  saveUserPermissions: vi.fn(),
  listUserToolGrants: vi.fn(),
  saveUserToolGrants: vi.fn(),
  listDepts: vi.fn(),
  listPermissions: vi.fn(),
  listTools: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/user', () => ({
  listUsers: mocks.listUsers,
  createUser: mocks.createUser,
  updateUser: mocks.updateUser,
  updateUserStatus: mocks.updateUserStatus,
  getUserPermissions: mocks.getUserPermissions,
  saveUserPermissions: mocks.saveUserPermissions,
  listUserToolGrants: mocks.listUserToolGrants,
  saveUserToolGrants: mocks.saveUserToolGrants,
  // P8 新增的导入导出入口：本文件只渲染页面，**仅需桩存在**（夹具对齐，未改任何断言）
  downloadUserTemplate: vi.fn(),
  exportUsers: vi.fn(),
  importUsers: vi.fn(),
}));

vi.mock('@/api/dept', () => ({
  listDepts: mocks.listDepts,
}));

vi.mock('@/api/permission', () => ({
  listPermissions: mocks.listPermissions,
}));

vi.mock('@/api/tool', () => ({
  listTools: mocks.listTools,
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { success: mocks.success, error: mocks.error, info: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: mocks.confirm },
  };
});

const USER = {
  id: '12',
  username: 'zhangsan',
  realName: '张三',
  deptId: '1',
  deptName: '研发部',
  status: 1,
  createTime: '2026-09-01 09:00:00',
};

const TOOL = {
  id: '2007',
  name: '日志分析器',
  deptName: '数据部',
  latestVersion: 'v2.1',
  intro: '分析日志',
  fileExt: 'zip',
  scope: 'DEPT',
  tags: [],
  uploaderName: '李四',
  fileSize: 1024,
  downloadCount: 3,
  updateTime: '2026-09-17 10:00:00',
};

let pinia: Pinia;

async function mountView(): Promise<VueWrapper> {
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'admin',
      realName: '管理员',
      deptId: '1',
      deptName: '研发部',
      permissions: ['user:manage', 'user:grant'],
    },
  });

  const wrapper = mount(UserView, { global: { plugins: [pinia, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

/** 打开「分配权限」并展开跨部门授权面板 */
async function openPermissionDialog(wrapper: VueWrapper): Promise<void> {
  const open = wrapper.findAll('button').find((item) => item.text().includes('分配权限'));
  await open?.trigger('click');
  await flushPromises();

  await wrapper.find('.el-collapse-item__header').trigger('click');
  await flushPromises();
}

function dialogSaveButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find((item) => item.text().includes('保存'));
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  Object.values(mocks).forEach((mock) => mock.mockReset());

  mocks.listUsers.mockResolvedValue({ list: [USER], total: 1, pageNum: 1, pageSize: 20 });
  mocks.listDepts.mockResolvedValue([{ id: '1', name: '研发部', remark: '', status: 1, userCount: 1, createTime: '' }]);
  mocks.listPermissions.mockResolvedValue([
    {
      groupCode: 'tool',
      groupName: '工具',
      items: [
        { code: 'tool:download', name: '下载工具', description: '下载工具文件' },
        { code: 'tool:upload', name: '上传工具', description: '上传新工具' },
      ],
    },
  ]);
  mocks.getUserPermissions.mockResolvedValue(['tool:download']);
  mocks.listUserToolGrants.mockResolvedValue([]);
  mocks.listTools.mockResolvedValue({ list: [TOOL], total: 1, pageNum: 1, pageSize: 100 });
  mocks.saveUserPermissions.mockResolvedValue(null);
  mocks.saveUserToolGrants.mockResolvedValue(null);
  mocks.createUser.mockResolvedValue('99');
});

describe('分配权限 · 两接口串行提交（A8）', () => {
  it('保存：先 permissions 后 tool-grants，顺序正确且共 2 次', async () => {
    const wrapper = await mountView();
    await openPermissionDialog(wrapper);

    // 勾选一个跨部门工具
    await wrapper.find('.tb-perm__li').trigger('click');
    await flushPromises();

    await dialogSaveButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.saveUserPermissions).toHaveBeenCalledTimes(1);
    expect(mocks.saveUserToolGrants).toHaveBeenCalledTimes(1);
    expect(mocks.saveUserPermissions).toHaveBeenCalledWith('12', { permissions: ['tool:download'] });
    expect(mocks.saveUserToolGrants).toHaveBeenCalledWith('12', { toolIds: ['2007'] });

    const permissionOrder = mocks.saveUserPermissions.mock.invocationCallOrder[0] ?? 0;
    const grantOrder = mocks.saveUserToolGrants.mock.invocationCallOrder[0] ?? 0;
    expect(permissionOrder).toBeLessThan(grantOrder);
  });

  it('授权面板未展开：只提交权限点，不碰授权（不清空已有授权）', async () => {
    const wrapper = await mountView();
    const open = wrapper.findAll('button').find((item) => item.text().includes('分配权限'));
    await open?.trigger('click');
    await flushPromises();

    await dialogSaveButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.saveUserPermissions).toHaveBeenCalledTimes(1);
    expect(mocks.saveUserToolGrants).not.toHaveBeenCalled();
  });

  it('第一个请求失败：就地提示、不发起第二个请求、弹窗保留（C5）', async () => {
    mocks.saveUserPermissions.mockRejectedValue(new Error('权限保存失败'));
    const wrapper = await mountView();
    await openPermissionDialog(wrapper);

    await dialogSaveButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.saveUserToolGrants).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('权限保存失败');
    // 弹窗未关闭
    expect(wrapper.text()).toContain('分配权限 · 张三');
  });

  it('第二个请求失败：提示"权限已保存，但额外授权保存失败"，弹窗保留（C4）', async () => {
    mocks.saveUserToolGrants.mockRejectedValue(new Error('网络异常'));
    const wrapper = await mountView();
    await openPermissionDialog(wrapper);

    await dialogSaveButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.saveUserPermissions).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('权限已保存，但额外授权保存失败');
    expect(wrapper.text()).toContain('分配权限 · 张三');
    expect(mocks.success).not.toHaveBeenCalled();
  });

  it('清空权限点 + 取消二次确认：一个请求都不发（C6）', async () => {
    mocks.confirm.mockRejectedValue(new Error('cancel'));
    const wrapper = await mountView();
    const open = wrapper.findAll('button').find((item) => item.text().includes('分配权限'));
    await open?.trigger('click');
    await flushPromises();

    // 取消唯一的已勾选权限点（[0] 为全选）
    const boxes = wrapper.findAll('.el-dialog input[type="checkbox"]');
    await boxes[1]?.setValue(false);
    await flushPromises();
    expect(wrapper.text()).toContain('已选 0 / 2 项');

    await dialogSaveButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.confirm).toHaveBeenCalledTimes(1);
    expect(mocks.saveUserPermissions).not.toHaveBeenCalled();
    expect(mocks.saveUserToolGrants).not.toHaveBeenCalled();
  });
});

describe('新建账号默认权限（A10）', () => {
  it('默认权限仅含 tool:download', () => {
    expect(DEFAULT_NEW_USER_PERMISSIONS).toEqual(['tool:download']);
  });

  it('建号请求带上默认权限，且成功提示不提"上传"（B6）', async () => {
    const wrapper = await mountView();

    wrapper.findComponent(UserFormDialog).vm.$emit('submit', {
      username: 'lisi',
      realName: '李四',
      password: '123456',
      deptId: '1',
    });
    await flushPromises();

    expect(mocks.createUser).toHaveBeenCalledWith(
      expect.objectContaining({ username: 'lisi', permissions: ['tool:download'] }),
    );

    const message = String(mocks.success.mock.calls[0]?.[0] ?? '');
    expect(message).toContain('下载');
    expect(message).not.toContain('上传');
  });
});
