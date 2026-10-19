/**
 * 测试范围：管理页的失败分支与权限显隐（DeptView / TagView / DeletedView）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"停用失败原因可见且开关不被带偏""删除标签提示影响面""恢复流程""无权限不渲染入口"（P4 A4 / A5 / A7 / A8）
 * 防回归问题：停用失败仍把开关切过去（界面与服务端不一致）、删除提示不说影响几个工具、无权限仍渲染操作按钮
 * 关键不变量：
 *   - 部门停用失败 → 展示可读原因，开关维持服务端状态
 *   - 标签删除确认文案含关联工具数量，并说明不删除工具；取消则不调接口
 *   - 已删除工具恢复需二次确认，成功后重新拉取列表
 *   - 无对应权限点时相关按钮不渲染
 * 边界说明：不覆盖真实接口；分页与大数据量在 P5 验证
 * 依赖与限制：接口模块为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import ElementPlus from 'element-plus';
import { useUserStore } from '@/stores/user';
import DeptView from '@/views/admin/DeptView.vue';
import TagView from '@/views/admin/TagView.vue';
import DeletedView from '@/views/admin/DeletedView.vue';

const mocks = vi.hoisted(() => ({
  listDepts: vi.fn(),
  updateDeptStatus: vi.fn(),
  listTags: vi.fn(),
  deleteTag: vi.fn(),
  listDeletedTools: vi.fn(),
  restoreTool: vi.fn(),
  // 彻底删除 / 清空：仅需桩存在（本文件不逐一断言）
  purgeTools: vi.fn(),
  clearDeletedTools: vi.fn(),
  confirm: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}));

vi.mock('@/api/dept', () => ({
  listDepts: mocks.listDepts,
  createDept: vi.fn(),
  updateDept: vi.fn(),
  updateDeptStatus: mocks.updateDeptStatus,
  // P8 新增的导入导出入口：本文件只渲染页面，**仅需桩存在**（夹具对齐，未改任何断言）
  downloadDeptTemplate: vi.fn(),
  exportDepts: vi.fn(),
  importDepts: vi.fn(),
}));

vi.mock('@/api/tag', () => ({
  listTags: mocks.listTags,
  createTag: vi.fn(),
  updateTag: vi.fn(),
  deleteTag: mocks.deleteTag,
}));

vi.mock('@/api/tool', () => ({
  listDeletedTools: mocks.listDeletedTools,
  restoreTool: mocks.restoreTool,
  purgeTools: mocks.purgeTools,
  clearDeletedTools: mocks.clearDeletedTools,
  toolDownloadUrl: (id: string) => `/api/tools/${id}/download`,
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { success: mocks.success, error: mocks.error, info: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: mocks.confirm },
  };
});

let pinia: Pinia;

const DEPT = {
  id: '1',
  name: '研发部',
  remark: '负责平台研发',
  status: 1,
  userCount: 3,
  createTime: '2026-09-01 09:00:00',
};

const TAG = { id: '1', name: '数据处理', toolCount: 3, createTime: '2026-09-01 09:00:00' };

const DELETED_TOOL = {
  id: '1001',
  name: '数据清洗小工具',
  latestVersion: 'v1.2',
  intro: '批量清洗 CSV',
  fileExt: 'zip',
  scope: 'DEPT',
  tags: [],
  uploaderName: '张三',
  deptName: '研发部',
  fileSize: 2048,
  downloadCount: 7,
  updateTime: '2026-09-17 10:00:00',
};

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

async function mountView(component: unknown, permissions: string[]): Promise<VueWrapper> {
  signIn(permissions);
  const wrapper = mount(component as never, { global: { plugins: [pinia, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  Object.values(mocks).forEach((mock) => mock.mockReset());
  mocks.listDepts.mockResolvedValue([DEPT]);
  mocks.listTags.mockResolvedValue([TAG]);
  mocks.listDeletedTools.mockResolvedValue({ list: [DELETED_TOOL], total: 1, pageNum: 1, pageSize: 20 });
});

describe('部门管理', () => {
  it('停用失败：展示后端原因，开关维持原状（A4）', async () => {
    mocks.confirm.mockResolvedValue('confirm');
    mocks.updateDeptStatus.mockRejectedValue(new Error('该部门下仍有 3 个用户，请先调整用户归属'));

    const wrapper = await mountView(DeptView, ['dept:manage']);
    const toggle = wrapper.get('.el-switch');
    expect(toggle.classes()).toContain('is-checked');

    await toggle.trigger('click');
    await flushPromises();

    expect(mocks.updateDeptStatus).toHaveBeenCalledWith('1', { status: 0 });
    expect(mocks.error).toHaveBeenCalledWith(expect.stringContaining('仍有 3 个用户'));
    // 失败后开关不得被带偏：仍保持服务端的启用状态
    expect(wrapper.get('.el-switch').classes()).toContain('is-checked');
  });

  it('无部门管理权限：不渲染新建与编辑入口（A8）', async () => {
    const wrapper = await mountView(DeptView, []);

    expect(wrapper.text()).not.toContain('新建部门');
    expect(wrapper.findAll('button').some((item) => item.text() === '编辑')).toBe(false);
  });
});

describe('标签管理', () => {
  it('删除提示含关联工具数量且说明不删工具；取消则不调用接口（A5）', async () => {
    mocks.confirm.mockRejectedValue(new Error('cancel'));

    const wrapper = await mountView(TagView, ['tag:manage']);
    const deleteButton = wrapper.findAll('button').find((item) => item.text() === '删除');
    await deleteButton?.trigger('click');
    await flushPromises();

    const message = String(mocks.confirm.mock.calls[0]?.[0]);
    expect(message).toContain('3 个工具');
    expect(message).toContain('不会删除');
    expect(mocks.deleteTag).not.toHaveBeenCalled();
  });

  it('确认删除后调用接口并刷新列表（A5）', async () => {
    mocks.confirm.mockResolvedValue('confirm');
    mocks.deleteTag.mockResolvedValue(null);

    const wrapper = await mountView(TagView, ['tag:manage']);
    const deleteButton = wrapper.findAll('button').find((item) => item.text() === '删除');
    await deleteButton?.trigger('click');
    await flushPromises();

    expect(mocks.deleteTag).toHaveBeenCalledWith('1');
    expect(mocks.listTags).toHaveBeenCalledTimes(2); // 初次加载 + 删除后刷新
  });

  it('无标签管理权限：不渲染新增与操作入口（A8）', async () => {
    const wrapper = await mountView(TagView, []);

    expect(wrapper.text()).not.toContain('新增标签');
    expect(wrapper.findAll('button').some((item) => item.text() === '删除')).toBe(false);
  });
});

describe('已删除工具', () => {
  it('恢复需二次确认，成功后重新拉取列表（A7）', async () => {
    mocks.confirm.mockResolvedValue('confirm');
    mocks.restoreTool.mockResolvedValue(null);

    const wrapper = await mountView(DeletedView, ['tool:delete']);
    expect(wrapper.text()).toContain('数据清洗小工具');

    const restoreButton = wrapper.findAll('button').find((item) => item.text() === '恢复');
    await restoreButton?.trigger('click');
    await flushPromises();

    expect(mocks.confirm).toHaveBeenCalledTimes(1);
    expect(mocks.restoreTool).toHaveBeenCalledWith('1001');
    expect(mocks.listDeletedTools).toHaveBeenCalledTimes(2);
  });

  it('恢复取消：不调用接口（A7）', async () => {
    mocks.confirm.mockRejectedValue(new Error('cancel'));

    const wrapper = await mountView(DeletedView, ['tool:delete']);
    const restoreButton = wrapper.findAll('button').find((item) => item.text() === '恢复');
    await restoreButton?.trigger('click');
    await flushPromises();

    expect(mocks.restoreTool).not.toHaveBeenCalled();
  });

  /**
   * A8（**v1.21 行为变更**）：回收站对登录用户开放 —— 无 `tool:delete` 也能看到「恢复」；
   * 但「彻底删除 / 清空」仍是管理动作，不渲染。
   * （旧断言为"无 tool:delete 连恢复都不渲染"，随契约 v1.21 废弃：上传人要能自救误删版本。）
   */
  it('无删除权限：可恢复本人记录，但不渲染彻底删除/清空（A8，v1.21 行为变更）', async () => {
    const wrapper = await mountView(DeletedView, []);

    expect(wrapper.findAll('button').some((item) => item.text() === '恢复')).toBe(true);
    expect(wrapper.findAll('button').some((item) => item.text() === '清空')).toBe(false);
    expect(wrapper.findAll('button').some((item) => item.text() === '删除')).toBe(false);
  });
});
