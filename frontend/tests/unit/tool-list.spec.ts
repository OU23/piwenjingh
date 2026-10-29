/**
 * 测试范围：工具列表页（src/views/tool/ToolListView.vue）——四态、两种空态、参数拼装、权限显隐
 * 测试类型：组件（jsdom）
 * 测试目的：锁死列表页的四种状态与两种空态分支，以及查询参数口径
 * 防回归问题：只做"有数据"一种状态、空态不区分场景、无权限仍渲染上传入口、分页参数丢失
 * 关键不变量：
 *   - 加载中 / 有数据 / 空数据 / 请求失败四态各有渲染
 *   - 无筛选无数据显示"还没有任何工具"，有筛选无结果显示"没有匹配的工具"
 *   - 无 tool:upload 权限不渲染上传入口；无 tool:download 不渲染下载入口
 * 边界说明：不覆盖真实接口与真实分页交互（翻页请求在 P5 联调验证）
 * 依赖与限制：接口模块为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import type { ToolVO } from '@/api/tool';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import ToolListView from '@/views/tool/ToolListView.vue';

const mocks = vi.hoisted(() => ({
  listTools: vi.fn(),
  listTags: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listTools: mocks.listTools,
  toolDownloadUrl: (id: string, versionId?: string) =>
    `/api/tools/${id}/download${versionId ? `?versionId=${versionId}` : ''}`,
}));

vi.mock('@/api/tag', () => ({
  listTags: mocks.listTags,
}));

let pinia: Pinia;
let router: Router;

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

function page(list: ToolVO[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 20 };
}

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

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  mocks.listTools.mockReset();
  mocks.listTags.mockReset();
  mocks.listTags.mockResolvedValue([]);
});

describe('工具列表页', () => {
  it('有数据：按契约字段渲染列表，并带上默认查询参数', async () => {
    mocks.listTools.mockResolvedValue(page([TOOL]));
    const wrapper = await mountList();

    expect(mocks.listTools).toHaveBeenCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 20, sortBy: 'createTime', sortOrder: 'desc' }),
    );

    const text = wrapper.text();
    expect(text).toContain('数据清洗小工具');
    expect(text).toContain('v1.2');
    expect(text).toContain('.zip');
    expect(text).toContain('数据处理');
    expect(text).toContain('研发部');
    expect(text).toContain('2.0 KB');
    expect(text).toContain('2026-09-17 10:00:00');
    expect(text).toContain('共 1 个工具');
  });

  it('加载中：渲染加载态占位', async () => {
    mocks.listTools.mockReturnValue(new Promise(() => undefined));
    const wrapper = await mountList();

    expect(wrapper.find('.el-skeleton').exists()).toBe(true);
  });

  it('请求失败：渲染失败态与重试入口，不显示空态', async () => {
    mocks.listTools.mockRejectedValue(new Error('服务器开小差了，请稍后重试'));
    const wrapper = await mountList();

    const text = wrapper.text();
    expect(text).toContain('服务器开小差了，请稍后重试');
    expect(text).toContain('重试');
    expect(text).not.toContain('还没有任何工具');
  });

  it('空数据（无筛选）：引导上传', async () => {
    mocks.listTools.mockResolvedValue(page([]));
    const wrapper = await mountList(['tool:upload']);

    const text = wrapper.text();
    expect(text).toContain('还没有任何工具');
    expect(text).toContain('上传第一个工具后，团队成员就能在这里检索与下载');
    expect(text).not.toContain('没有匹配的工具');
  });

  it('筛选无结果：引导重置筛选，且可一键重置', async () => {
    mocks.listTools.mockResolvedValueOnce(page([TOOL]));
    const wrapper = await mountList();

    mocks.listTools.mockResolvedValueOnce(page([]));
    const input = wrapper.get('.tb-filter__item.is-wide input');
    await input.setValue('不存在的工具');
    await input.trigger('keyup.enter');
    await flushPromises();

    // 第二步：带筛选条件重查，且页码回到第一页
    expect(mocks.listTools).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '不存在的工具', pageNum: 1 }),
    );

    expect(wrapper.text()).toContain('没有匹配的工具');
    expect(wrapper.text()).toContain('重置筛选');

    // 重置后清空关键词重新查询
    mocks.listTools.mockResolvedValueOnce(page([TOOL]));
    const resetButton = wrapper
      .findAll('button')
      .find((item) => item.text().includes('重置筛选') || item.text() === '重置');
    await resetButton?.trigger('click');
    await flushPromises();

    expect(mocks.listTools).toHaveBeenLastCalledWith(expect.objectContaining({ pageNum: 1 }));
    const lastQuery = mocks.listTools.mock.calls.at(-1)?.[0] as Record<string, unknown>;
    expect(lastQuery.keyword).toBeUndefined();
  });

  it('权限显隐：无工具上传权限时不渲染上传入口，有下载权限才渲染下载入口', async () => {
    mocks.listTools.mockResolvedValue(page([TOOL]));

    const noPermission = await mountList([]);
    expect(noPermission.get('.tb-page__toolbar').text()).not.toContain('上传工具');
    expect(noPermission.findAll('.tb-table__ops button').length).toBe(0);

    const withUpload = await mountList(['tool:upload', 'tool:download']);
    expect(withUpload.get('.tb-page__toolbar').text()).toContain('上传工具');
    // 操作列按钮具名断言（P15 口径对齐）：`tool:download` → 「下载」。
    // 「可见范围」需 `user:manage`+`user:grant`（契约 v1.15，原 P14 的 `tool:upload` 口径已作废）、
    // 「删除」需 `tool:delete`、「授权」需 `user:grant` → 本用例权限均未含，操作列只应有「下载」。
    // （原为"按钮数 = 1"，改为精确具名列举，断言强度不降）
    expect(withUpload.findAll('.tb-table__ops button').map((item) => item.text())).toEqual(['下载']);
  });
});
