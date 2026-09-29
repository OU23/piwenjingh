/**
 * 测试范围：权限分配弹窗（src/components/PermissionDialog.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：
 *   - 锁死"权限字典取自接口""全量覆盖提交""清空需二次确认且取消不发请求"（P4 验收 A1 / A2 / C6）
 *   - 锁死 P6（M-17）的界面改造与跨部门授权折叠面板（P6 验收 A2 ~ A7）
 * 防回归问题：把权限点硬编码在前端、只提交增量、清空权限未经确认、取消后仍发请求；
 *   权限点退回单列平铺、描述常驻同行、弹窗内又冒出解释性提示条、折叠面板默认展开并抢跑请求、
 *   已选工具被搜索条件过滤掉导致丢勾选、长列表改用"加载更多"
 * 关键不变量：
 *   - 分组与权限点全部来自接口返回值（换一份字典即换渲染结果）；组名字面量不写进代码
 *   - 保存提交的是**当前勾选的完整集合**；空集合保存前必须二次确认，取消则不 emit 保存
 *   - 授权面板**默认收起且收起态零请求**；**未展开过**时提交的 `toolIds` 为 `null`（调用方跳过授权提交）
 *   - 已授权的工具**置顶保留**，不受关键词 / 部门筛选影响
 * 边界说明：不覆盖真实接口与后端校验；两接口串行编排见 user-tool-grant-save.spec.ts
 * 依赖与限制：权限字典、用户权限、工具授权均为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import ElementPlus from 'element-plus';
import PermissionDialog from '@/components/PermissionDialog.vue';

const mocks = vi.hoisted(() => ({
  listPermissions: vi.fn(),
  getUserPermissions: vi.fn(),
  listUserToolGrants: vi.fn(),
  listTools: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/permission', () => ({
  listPermissions: mocks.listPermissions,
}));

vi.mock('@/api/user', () => ({
  getUserPermissions: mocks.getUserPermissions,
  listUserToolGrants: mocks.listUserToolGrants,
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

/** 已授权工具（刻意**不在**候选结果里，用于验证"置顶保留不丢勾选"） */
const GRANTED_TOOL = {
  id: '2001',
  name: '已授权工具',
  deptName: '财务部',
  latestVersion: 'v1.2',
};

/** 候选工具 */
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
};

/** 第二个候选工具（用于「全选 / 取消全选」用例） */
const CANDIDATE_TOOL_2 = {
  ...CANDIDATE_TOOL,
  id: '2008',
  name: '报表导出工具',
  deptName: '财务部',
};

function page(list: unknown[]): { list: unknown[]; total: number; pageNum: number; pageSize: number } {
  return { list, total: list.length, pageNum: 1, pageSize: 100 };
}

let pinia: Pinia;

async function mountDialog(): Promise<VueWrapper> {
  const wrapper = mount(PermissionDialog, {
    props: { modelValue: true, user: USER },
    global: { plugins: [pinia, ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

/** 展开「额外可下载工具」折叠面板 */
async function expandGrantPanel(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('.el-collapse-item__header').trigger('click');
  await flushPromises();
}

function saveButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find((item) => item.text().includes('保存'));
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  Object.values(mocks).forEach((mock) => mock.mockReset());

  // 刻意用一份"非项目默认"的字典，用来证明渲染结果来自接口而非硬编码
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
  mocks.listTools.mockResolvedValue(page([]));
});

describe('权限分配弹窗 · 既有语义（P4）', () => {
  it('权限字典取自接口（A1）：分组与权限点随接口变化', async () => {
    const wrapper = await mountDialog();

    expect(mocks.listPermissions).toHaveBeenCalledTimes(1);
    expect(mocks.getUserPermissions).toHaveBeenCalledWith('12');
    expect(wrapper.text()).toContain('自定义分组');
    expect(wrapper.text()).toContain('只读');
    expect(wrapper.text()).toContain('可写');
    // 初始勾选来自接口返回的已分配权限
    expect(wrapper.text()).toContain('已选 1 / 2 项');
  });

  it('保存为全量覆盖（A2）：提交当前勾选的完整集合', async () => {
    const wrapper = await mountDialog();

    const boxes = wrapper.findAll('input[type="checkbox"]');
    // [0] 为全选，其后依次是各权限点
    await boxes[2]?.setValue(true);
    await flushPromises();

    expect(wrapper.text()).toContain('已选 2 / 2 项');

    await saveButton(wrapper)?.trigger('click');
    await flushPromises();

    // 面板未展开过 → toolIds 为 null（调用方据此跳过授权提交，避免误清空）
    expect(wrapper.emitted('save')?.[0]).toEqual([{ codes: ['custom:read', 'custom:write'], toolIds: null }]);
    // 有勾选时不弹二次确认
    expect(mocks.confirm).not.toHaveBeenCalled();
  });

  it('清空权限：先二次确认，取消则不发起保存（C6）', async () => {
    const wrapper = await mountDialog();

    const boxes = wrapper.findAll('input[type="checkbox"]');
    await boxes[1]?.setValue(false); // 取消唯一的已选权限
    await flushPromises();
    expect(wrapper.text()).toContain('已选 0 / 2 项');

    mocks.confirm.mockRejectedValue(new Error('cancel'));
    await saveButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.confirm).toHaveBeenCalledTimes(1);
    expect(String(mocks.confirm.mock.calls[0]?.[0])).toContain('清空权限');
    expect(wrapper.emitted('save')).toBeUndefined();
  });

  it('清空权限：确认后提交空集合', async () => {
    const wrapper = await mountDialog();

    const boxes = wrapper.findAll('input[type="checkbox"]');
    await boxes[1]?.setValue(false);
    await flushPromises();

    mocks.confirm.mockResolvedValue('confirm');
    await saveButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(wrapper.emitted('save')?.[0]).toEqual([{ codes: [], toolIds: null }]);
  });
});

describe('权限分配弹窗 · 界面改造（P6 A2 ~ A5）', () => {
  it('弹窗尺寸与列数（A2）：640px + 三列网格，不再单列平铺', async () => {
    const wrapper = await mountDialog();

    expect(wrapper.findComponent({ name: 'ElDialog' }).props('width')).toBe('640px');
    expect(wrapper.find('.tb-perm__grid.is-cols-3').exists()).toBe(true);
  });

  it('描述收进悬停提示（A3）：不再与名称同行常驻', async () => {
    const wrapper = await mountDialog();

    const items = wrapper.findAll('.tb-perm__item');
    expect(items[0]?.text()).not.toContain('仅查看');
    expect(items[1]?.text()).not.toContain('可修改');

    const tips = wrapper.findAllComponents({ name: 'ElTooltip' }).map((item) => item.props('content'));
    expect(tips).toContain('仅查看');
    expect(tips).toContain('可修改');
  });

  it('单点分组并排成一行（A4）：按"连续单点组"判定，与组名字面量无关', async () => {
    mocks.listPermissions.mockResolvedValue([
      {
        groupCode: 'g1',
        groupName: '甲组',
        items: [
          { code: 'a:read', name: '甲一', description: '说明甲一' },
          { code: 'a:write', name: '甲二', description: '说明甲二' },
        ],
      },
      { groupCode: 'g2', groupName: '乙组', items: [{ code: 'b:only', name: '乙一', description: '说明乙一' }] },
      { groupCode: 'g3', groupName: '丙组', items: [{ code: 'c:only', name: '丙一', description: '说明丙一' }] },
      { groupCode: 'g4', groupName: '丁组', items: [{ code: 'd:only', name: '丁一', description: '说明丁一' }] },
    ]);

    const wrapper = await mountDialog();
    const groups = wrapper.findAll('.tb-perm__group');

    // 甲组独占一行；乙/丙/丁三个单点组合并成一行
    expect(groups).toHaveLength(2);
    expect(groups[0]?.text()).toContain('甲组');
    expect(groups[1]?.text()).toContain('乙组 / 丙组 / 丁组');
    expect(groups[1]?.findAll('.tb-perm__item')).toHaveLength(3);
    // 合并后共用一个网格容器（并排渲染，而不是各占一行）
    expect(groups[1]?.findAll('.tb-perm__grid')).toHaveLength(1);
  });

  it('弹窗内无解释性提示条（A5）', async () => {
    const wrapper = await mountDialog();
    const text = wrapper.text();

    ['全量覆盖', '还需具备', '不含编辑', '追加版本', '重新登录或刷新'].forEach((word) => {
      expect(text).not.toContain(word);
    });
  });
});

describe('权限分配弹窗 · 跨部门授权折叠面板（P6 A6 / A7）', () => {
  it('默认收起且收起态零请求（A6）', async () => {
    const wrapper = await mountDialog();

    expect(wrapper.text()).toContain('额外可下载工具（跨部门）');
    // 0 个时不渲染计数标签：无信息量的标签只会显得突兀（需求方 2026-09-24）
    expect(wrapper.find('.tb-perm__fold-count').exists()).toBe(false);
    // 懒加载：收起状态下不请求已授权列表，也不请求工具候选
    expect(mocks.listUserToolGrants).not.toHaveBeenCalled();
    expect(mocks.listTools).not.toHaveBeenCalled();
  });

  it('展开后懒加载多选列表：竖向滚动、无"加载更多"、已选置顶保留（A7）', async () => {
    mocks.listUserToolGrants.mockResolvedValue([GRANTED_TOOL]);
    mocks.listTools.mockResolvedValue(page([CANDIDATE_TOOL]));

    const wrapper = await mountDialog();
    await expandGrantPanel(wrapper);

    expect(mocks.listUserToolGrants).toHaveBeenCalledWith('12');
    expect(mocks.listTools).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 100 }));

    const rows = wrapper.findAll('.tb-perm__li');
    expect(rows).toHaveLength(2);
    // 已授权工具**不在**候选结果里，仍置顶保留且保持勾选
    expect(rows[0]?.text()).toContain('已授权工具');
    expect(rows[0]?.classes()).toContain('is-on');
    expect(wrapper.text()).toContain('已选 1 个');

    // 固定高度 + 竖向滚动，而不是"加载更多"
    expect(wrapper.find('.tb-perm__list.is-scroll').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('加载更多');
    expect(wrapper.find('.el-pagination').exists()).toBe(false);
  });

  it('勾选工具后保存：payload 同时带上 toolIds（A8 前半）', async () => {
    mocks.listTools.mockResolvedValue(page([CANDIDATE_TOOL]));

    const wrapper = await mountDialog();
    await expandGrantPanel(wrapper);

    await wrapper.find('.tb-perm__li').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('已选 1 个');

    await saveButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(wrapper.emitted('save')?.[0]).toEqual([{ codes: ['custom:read'], toolIds: ['2007'] }]);
  });

  it('取消勾选：已选项移除后计数标签随之消失', async () => {
    mocks.listUserToolGrants.mockResolvedValue([GRANTED_TOOL]);
    mocks.listTools.mockResolvedValue(page([CANDIDATE_TOOL]));

    const wrapper = await mountDialog();
    await expandGrantPanel(wrapper);
    expect(wrapper.text()).toContain('已选 1 个');

    await wrapper.findAll('.tb-perm__li')[0]?.trigger('click');
    await flushPromises();

    // 回到 0 个 → 标签不再渲染（不再是灰底的「已选 0 个」）
    expect(wrapper.find('.tb-perm__fold-count').exists()).toBe(false);
  });

  it('全选 / 取消全选：一键完成（作用于当前结果）', async () => {
    mocks.listTools.mockResolvedValue(page([CANDIDATE_TOOL, CANDIDATE_TOOL_2]));

    const wrapper = await mountDialog();
    await expandGrantPanel(wrapper);

    const allButton = () => wrapper.findAll('button').find((item) => item.text().includes('全选'));

    expect(allButton()?.text()).toContain('全选当前结果');

    await allButton()?.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('已选 2 个');
    // 已全选 → 按钮切换为「取消全选」，不必逐个点取消
    expect(allButton()?.text()).toContain('取消全选');

    await allButton()?.trigger('click');
    await flushPromises();
    expect(wrapper.find('.tb-perm__fold-count').exists()).toBe(false);
  });
});
