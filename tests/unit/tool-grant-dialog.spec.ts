/**
 * 测试范围：工具侧「授权」弹窗（src/components/ToolGrantDialog.vue）与入口（ToolTable / ToolListView）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死"工具 → 多选用户"的授权入口（P6 / M-17，契约 v1.7 §9.8）
 * 防回归问题：入口不按权限显隐、保存请求体字段名写错、已选用户被搜索条件过滤掉导致丢勾选、
 *   列表改用"加载更多"、弹窗内又冒出解释性提示条
 * 关键不变量：
 *   - 入口按钮受 `user:grant` 控制，无权限**不渲染**
 *   - 保存走 `PUT /api/tools/{id}/grants`，请求体字段名为 `userIds`（**全量覆盖**，不是增量）
 *   - 已授权用户**置顶保留**，不受关键词 / 部门筛选影响
 *   - 列表为固定高度的竖向滚动容器，不使用"加载更多"
 * 边界说明：不覆盖真实接口与后端校验；与用户侧共用同一张表的一致性由后端保证
 * 依赖与限制：工具 / 用户接口均为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import type { ToolVO } from '@/api/tool';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import ToolGrantDialog from '@/components/ToolGrantDialog.vue';
import ToolListView from '@/views/tool/ToolListView.vue';

const mocks = vi.hoisted(() => ({
  listToolGrants: vi.fn(),
  saveToolGrants: vi.fn(),
  listTools: vi.fn(),
  listTags: vi.fn(),
  listUsers: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listToolGrants: mocks.listToolGrants,
  saveToolGrants: mocks.saveToolGrants,
  listTools: mocks.listTools,
  batchDeleteTools: vi.fn(),
  deleteTool: vi.fn(),
  toolDownloadUrl: (id: string, versionId?: string) =>
    `/api/tools/${id}/download${versionId ? `?versionId=${versionId}` : ''}`,
}));

vi.mock('@/api/tag', () => ({
  listTags: mocks.listTags,
}));

vi.mock('@/api/user', () => ({
  listUsers: mocks.listUsers,
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { success: mocks.success, error: mocks.error, info: vi.fn(), warning: vi.fn() },
  };
});

const TOOL: ToolVO = {
  id: '1001',
  name: '数据清洗小工具',
  latestVersion: 'v1.2',
  intro: '批量清洗 CSV',
  fileExt: 'zip',
  scope: 'DEPT',
  tags: [],
  uploaderName: '张三',
  uploaderId: '9',
  deptName: '研发部',
  fileSize: 2048,
  downloadCount: 7,
  updateTime: '2026-09-17 10:00:00',
};

/** 已授权用户（刻意**不在**候选结果里，用于验证"置顶保留不丢勾选"） */
const GRANTED_USER = { id: '25', username: 'wangwu', realName: '王五', deptName: '数据部' };

function makeUser(id: string, username: string, realName: string, deptName: string) {
  return {
    id,
    username,
    realName,
    deptId: '1',
    deptName,
    status: 1,
    createTime: '2026-09-01 09:00:00',
  };
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

async function mountDialog(): Promise<VueWrapper> {
  const wrapper = mount(ToolGrantDialog, {
    props: { modelValue: true, tool: TOOL },
    global: { plugins: [pinia, ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

async function mountList(permissions: string[]): Promise<VueWrapper> {
  signIn(permissions);
  await router.push('/tools');
  await router.isReady();

  const wrapper = mount(ToolListView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

function grantButtons(wrapper: VueWrapper) {
  return wrapper.findAll('button').filter((item) => item.text().includes('授权'));
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  Object.values(mocks).forEach((mock) => mock.mockReset());

  mocks.listToolGrants.mockResolvedValue([]);
  mocks.listUsers.mockResolvedValue(page([]));
  mocks.listTags.mockResolvedValue([]);
  mocks.listTools.mockResolvedValue(page([TOOL]));
  mocks.saveToolGrants.mockResolvedValue(null);
});

describe('工具侧授权弹窗', () => {
  it('打开即加载已授权用户与候选用户，并渲染 姓名 + 账号 + 部门（A9）', async () => {
    mocks.listToolGrants.mockResolvedValue([GRANTED_USER]);
    mocks.listUsers.mockResolvedValue(page([makeUser('18', 'lisi', '李四', '市场部')]));

    const wrapper = await mountDialog();

    expect(mocks.listToolGrants).toHaveBeenCalledWith('1001');
    expect(mocks.listUsers).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 100 }));

    const rows = wrapper.findAll('.tb-grant__li');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.text()).toContain('王五');
    expect(rows[0]?.text()).toContain('wangwu');
    expect(rows[0]?.text()).toContain('数据部');
    // 已授权用户置顶保留并标记
    expect(rows[0]?.classes()).toContain('is-on');

    const second = rows[1];
    expect(second?.text()).toContain('李四');
    expect(second?.text()).toContain('lisi');
    expect(second?.text()).toContain('市场部');
  });

  it('多选用户后保存：emit 全量 userIds（A9）', async () => {
    mocks.listToolGrants.mockResolvedValue([GRANTED_USER]);
    mocks.listUsers.mockResolvedValue(page([makeUser('18', 'lisi', '李四', '市场部')]));

    const wrapper = await mountDialog();

    // 再勾一个：已授权(25) + 新增(18)
    await wrapper.findAll('.tb-grant__li')[1]?.trigger('click');
    await flushPromises();

    const save = wrapper.findAll('button').find((item) => item.text().includes('保存'));
    await save?.trigger('click');

    expect(wrapper.emitted('save')?.[0]).toEqual([{ userIds: ['25', '18'] }]);
  });

  it('取消勾选已授权用户', async () => {
    mocks.listToolGrants.mockResolvedValue([GRANTED_USER]);

    const wrapper = await mountDialog();
    expect(wrapper.text()).toContain('已授权 1 个用户');

    await wrapper.findAll('.tb-grant__li')[0]?.trigger('click');
    await flushPromises();

    const save = wrapper.findAll('button').find((item) => item.text().includes('保存'));
    await save?.trigger('click');
    expect(wrapper.emitted('save')?.[0]).toEqual([{ userIds: [] }]);
  });

  it('全选 / 取消全选：一键完成，不必逐个点取消', async () => {
    mocks.listUsers.mockResolvedValue(
      // 注意：两个候选均非工具所属部门（研发部），避免被 T7 同部门收敛过滤掉，才能验证"全选全部可见候选"
      page([makeUser('18', 'lisi', '李四', '市场部'), makeUser('30', 'zhaoliu', '赵六', '财务部')]),
    );

    const wrapper = await mountDialog();
    const allButton = () => wrapper.findAll('button').find((item) => item.text().includes('全选'));

    expect(allButton()?.text()).toContain('全选当前结果');

    await allButton()?.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('已授权 2 个用户');
    // 已全选 → 按钮切换为「取消全选」
    expect(allButton()?.text()).toContain('取消全选');

    await allButton()?.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('已授权 0 个用户');
  });

  it('列表为竖向滚动容器，且没有"加载更多"（A7 同款口径）', async () => {
    mocks.listUsers.mockResolvedValue(page([makeUser('18', 'lisi', '李四', '市场部')]));

    const wrapper = await mountDialog();

    expect(wrapper.find('.tb-grant__list.is-scroll').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('加载更多');
    expect(wrapper.find('.el-pagination').exists()).toBe(false);
  });

  it('已选用户不在当前搜索结果中时仍显示且保持勾选', async () => {
    mocks.listToolGrants.mockResolvedValue([GRANTED_USER]);
    // 候选中没有王五（已授权），另有赵六会被关键词过滤掉
    mocks.listUsers.mockResolvedValue(
      page([makeUser('18', 'lisi', '李四', '市场部'), makeUser('30', 'zhaoliu', '赵六', '研发部')]),
    );

    const wrapper = await mountDialog();
    await wrapper.find('.tb-grant__tools input').setValue('李四');
    await flushPromises();

    const rows = wrapper.findAll('.tb-grant__li');
    const text = rows.map((row) => row.text()).join('|');
    // 已授权但不在搜索结果中的王五仍保留在最前
    expect(text).toContain('王五');
    expect(rows[0]?.classes()).toContain('is-on');
    // 关键词把不匹配的候选过滤掉
    expect(text).toContain('李四');
    expect(text).not.toContain('赵六');
  });
});

describe('工具列表页的「授权」入口（A9）', () => {
  it('有 user:grant 权限：渲染「授权」按钮，点击打开弹窗并加载授权', async () => {
    const wrapper = await mountList(['user:grant']);

    expect(grantButtons(wrapper)).toHaveLength(1);

    await grantButtons(wrapper)[0]?.trigger('click');
    await flushPromises();

    expect(mocks.listToolGrants).toHaveBeenCalledWith('1001');
    expect(wrapper.text()).toContain('授权下载 · 数据清洗小工具');
  });

  it('无 user:grant 权限：不渲染「授权」按钮', async () => {
    const wrapper = await mountList(['tool:download']);

    expect(grantButtons(wrapper)).toHaveLength(0);
  });

  it('保存调用 PUT /api/tools/{id}/grants，请求体为 { userIds }（A9）', async () => {
    mocks.listUsers.mockResolvedValue(page([makeUser('18', 'lisi', '李四', '市场部')]));
    const wrapper = await mountList(['user:grant']);

    await grantButtons(wrapper)[0]?.trigger('click');
    await flushPromises();

    await wrapper.find('.tb-grant__li').trigger('click');
    await flushPromises();

    const save = wrapper.findAll('button').find((item) => item.text().includes('保存'));
    await save?.trigger('click');
    await flushPromises();

    expect(mocks.saveToolGrants).toHaveBeenCalledWith('1001', { userIds: ['18'] });
    expect(mocks.success).toHaveBeenCalled();
  });

  it('保存失败：就地提示且弹窗保留（便于重试）', async () => {
    mocks.listUsers.mockResolvedValue(page([makeUser('18', 'lisi', '李四', '市场部')]));
    mocks.saveToolGrants.mockRejectedValue(new Error('授权保存失败'));
    const wrapper = await mountList(['user:grant']);

    await grantButtons(wrapper)[0]?.trigger('click');
    await flushPromises();

    await wrapper.find('.tb-grant__li').trigger('click');
    await flushPromises();

    const save = wrapper.findAll('button').find((item) => item.text().includes('保存'));
    await save?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('授权保存失败');
    // 弹窗未关闭：仍能看到弹窗标题
    expect(wrapper.text()).toContain('授权下载 · 数据清洗小工具');
  });
});
