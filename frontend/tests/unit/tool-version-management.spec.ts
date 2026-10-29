/**
 * 测试范围：列表「上传人」列与版本级删除入口（M-24 / 验收 A28、A29）
 * 测试类型：组件（jsdom）
 * 目的：锁死"上传人列在名称之前""删除入口按行判权（本人 / 双权限 / 全局管理员）"
 *       "删除版本走二次确认并调用物理删除接口（v1.22）"
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import ElementPlus from 'element-plus';
import ToolTable from '@/components/ToolTable.vue';
import ToolVersionTable from '@/components/ToolVersionTable.vue';
import { useUserStore } from '@/stores/user';
import type { ToolVO, ToolVersionVO } from '@/api/tool';

const mocks = vi.hoisted(() => ({
  deleteToolVersion: vi.fn(),
  getDownloadUrl: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  deleteToolVersion: mocks.deleteToolVersion,
  getDownloadUrl: mocks.getDownloadUrl,
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

function signIn(userId: string, permissions: string[]): void {
  useUserStore().setLogin({
    token: 'token-value',
    user: { userId, username: 'u', realName: '张三', deptId: '1', deptName: '研发部', permissions },
  });
}

const TOOL: ToolVO = {
  id: '1001',
  name: '数据清洗小工具',
  latestVersion: 'v1.2',
  intro: '批量清洗 CSV',
  fileExt: 'zip',
  scope: 'DEPT',
  tags: [],
  uploaderName: '张三',
  uploaderId: '1',
  deptName: '研发部',
  fileSize: 2048,
  downloadCount: 7,
  updateTime: '2026-09-17 10:00:00',
  toolType: 'FILE',
  linkUrl: null,
};

const VERSION: ToolVersionVO = {
  id: 'v1',
  version: 'v1.0',
  fileName: 'demo.zip',
  fileExt: 'zip',
  fileSize: 1024,
  uploaderName: '张三',
  uploadTime: '2026-09-17 10:00:00',
  downloadCount: 0,
  isLatest: true,
};

beforeEach(() => {
  sessionStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
  Object.values(mocks).forEach((fn) => fn.mockReset());
  mocks.confirm.mockResolvedValue('confirm');
});

async function mountToolTable(): Promise<VueWrapper> {
  const wrapper = mount(ToolTable, { props: { list: [TOOL] }, global: { plugins: [pinia, ElementPlus] } });
  await flushPromises(); // el-table 异步渲染列
  return wrapper;
}

async function mountVersionTable(perms: string[], uploaderId = '1'): Promise<VueWrapper> {
  signIn('1', perms);
  const wrapper = mount(ToolVersionTable, {
    props: { toolId: '1001', versions: [VERSION], uploaderId },
    global: { plugins: [pinia, ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

describe('工具列表：上传人列与删除入口', () => {
  it('「上传人」列位于「名称」列之前（A28）', async () => {
    signIn('1', []);
    const headers = (await mountToolTable()).findAll('th').map((th) => th.text());
    expect(headers).toContain('上传人');
    expect(headers.indexOf('上传人')).toBeLessThan(headers.indexOf('名称'));
  });

  it('上传人本人（无任何权限点）可见删除入口（A29）', async () => {
    signIn('1', []);
    const buttons = (await mountToolTable()).findAll('button');
    expect(buttons.some((b) => b.text() === '删除')).toBe(true);
  });

  it('非上传人且无权限 → 不渲染删除入口（A29）', async () => {
    signIn('2', []);
    const buttons = (await mountToolTable()).findAll('button');
    expect(buttons.some((b) => b.text() === '删除')).toBe(false);
  });

  it('双权限管理员（user:manage + user:grant）→ 可见删除入口（A29）', async () => {
    signIn('2', ['user:manage', 'user:grant']);
    const buttons = (await mountToolTable()).findAll('button');
    expect(buttons.some((b) => b.text() === '删除')).toBe(true);
  });
});

describe('版本列表：永久删除入口（A29 / v1.22）', () => {
  it('上传人本人：渲染删除按钮，二次确认后调物理删除接口', async () => {
    mocks.deleteToolVersion.mockResolvedValue(null);
    const wrapper = await mountVersionTable([]);

    const del = wrapper.findAll('button').find((b) => b.text() === '删除');
    expect(del).toBeTruthy();

    await del!.trigger('click');
    await flushPromises();

    expect(mocks.confirm).toHaveBeenCalledTimes(1);
    expect(mocks.deleteToolVersion).toHaveBeenCalledWith('1001', 'v1');
    expect(wrapper.emitted('deleted')).toBeTruthy();
  });

  it('非上传人且无权限 → 不渲染删除按钮', async () => {
    const wrapper = await mountVersionTable([], '9');
    expect(wrapper.findAll('button').some((b) => b.text() === '删除')).toBe(false);
  });
});
