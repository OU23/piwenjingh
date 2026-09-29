/**
 * 测试范围：工具详情页（src/views/tool/ToolDetailView.vue）——信息完整、换行保留、版本倒序、错误态、按钮显隐
 * 测试类型：组件（jsdom）
 * 测试目的：保证详情页在"正常 / 不存在 / 已删除 / 无权限 / 网络失败"下都有明确结果，不白屏
 * 防回归问题：使用说明换行被吞、版本顺序不可信、错误时白屏、无权限仍显示下载入口
 * 关键不变量：
 *   - 使用说明以纯文本渲染并保留换行（pre）
 *   - 版本列表**暂时隐藏**（ToolDetailView 的 SHOW_VERSION_LIST=false；组件与数据保留待恢复）
 *   - 不存在 / 已删除、可见范围外、其他失败三种错误各有明确文案
 * 边界说明：不覆盖真实接口与下载传输（下载核心另有测试）
 * 依赖与限制：接口模块为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { BizError, ForbiddenError } from '@/api/request';
import type { ToolDetailVO } from '@/api/tool';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import ToolDetailView from '@/views/tool/ToolDetailView.vue';

const mocks = vi.hoisted(() => ({
  getToolDetail: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  getToolDetail: mocks.getToolDetail,
  toolDownloadUrl: (id: string, versionId?: string) =>
    `/api/tools/${id}/download${versionId ? `?versionId=${versionId}` : ''}`,
}));

let pinia: Pinia;
let router: Router;

const DETAIL: ToolDetailVO = {
  id: '1001',
  name: '数据清洗小工具',
  latestVersion: 'v1.2',
  intro: '批量清洗 CSV 的工具',
  readme: '第一步：解压\n第二步：运行 run.bat\n第三步：等待完成',
  fileExt: 'zip',
  platform: 'Windows',
  dependency: '需要 Java 17 与 Maven 3.9',
  scope: 'PUBLIC',
  tags: [{ id: '1', name: '数据处理' }],
  uploaderId: '1',
  uploaderName: '张三',
  deptId: '1',
  deptName: '研发部',
  fileSize: 2048,
  downloadCount: 7,
  createTime: '2026-09-01 09:00:00',
  updateTime: '2026-09-17 10:00:00',
  versions: [
    {
      id: 'v2',
      version: 'v1.2',
      fileName: 'tool-v1.2.zip',
      fileExt: 'zip',
      fileSize: 2048,
      uploaderName: '张三',
      uploadTime: '2026-09-17 10:00:00',
      downloadCount: 7,
      isLatest: true,
    },
    {
      id: 'v1',
      version: 'v1.0',
      fileName: 'tool-v1.0.zip',
      fileExt: 'zip',
      fileSize: 1024,
      uploaderName: '张三',
      uploadTime: '2026-09-01 09:00:00',
      downloadCount: 2,
      isLatest: false,
    },
  ],
};

async function mountDetail(permissions: string[] = []): Promise<VueWrapper> {
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

  await router.push('/tools/1001');
  await router.isReady();

  const wrapper = mount(ToolDetailView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  mocks.getToolDetail.mockReset();
});

describe('工具详情页', () => {
  it('渲染基本信息与保留换行的使用说明（版本列表当前隐藏）', async () => {
    mocks.getToolDetail.mockResolvedValue(DETAIL);
    const wrapper = await mountDetail(['tool:download']);

    const text = wrapper.text();
    expect(text).toContain('数据清洗小工具');
    expect(text).toContain('Windows');
    expect(text).toContain('需要 Java 17 与 Maven 3.9');
    expect(text).toContain('全员可见');
    expect(text).toContain('2.0 KB');

    // 使用说明为纯文本且保留换行
    const readme = wrapper.get('.tb-info__readme');
    expect(readme.element.tagName).toBe('PRE');
    expect(readme.text().split('\n').length).toBe(3);
    expect(readme.text()).toContain('第二步：运行 run.bat');

    // 版本列表仍**暂时隐藏**（ToolDetailView 的 SHOW_VERSION_LIST=false）：
    // 组件 ToolVersionTable 与接口 versions 数据均保留，待产品确认后恢复（属"行为变更、断言随行为更新"）
    expect(wrapper.text()).not.toContain('版本列表');
    expect(wrapper.findAll('.tb-ver__no').length).toBe(0);
  });

  it('工具不存在或已删除：给出明确提示并可返回列表', async () => {
    mocks.getToolDetail.mockRejectedValue(new BizError(2004, '该工具不存在或已被删除'));
    const wrapper = await mountDetail();

    expect(wrapper.text()).toContain('该工具不存在或已被删除');
    expect(wrapper.text()).toContain('返回工具列表');
  });

  it('不在可见范围内：提示无权限而非通用错误', async () => {
    mocks.getToolDetail.mockRejectedValue(new ForbiddenError('无操作权限'));
    const wrapper = await mountDetail();

    expect(wrapper.text()).toContain('该工具不在你的可见范围内');
  });

  it('其他失败：展示后端可读原因', async () => {
    mocks.getToolDetail.mockRejectedValue(new Error('详情加载失败，请稍后重试'));
    const wrapper = await mountDetail();

    expect(wrapper.text()).toContain('详情加载失败，请稍后重试');
  });

  it('按钮显隐：下载按 tool:download，上传新版本按 tool:upload', async () => {
    mocks.getToolDetail.mockResolvedValue(DETAIL);

    const withoutPermission = await mountDetail([]);
    expect(withoutPermission.findAll('.tb-detail__ops button').length).toBe(0);

    const withDownload = await mountDetail(['tool:download']);
    expect(withDownload.get('.tb-detail__ops').text()).toContain('下载');
    expect(withDownload.get('.tb-detail__ops').text()).not.toContain('上传新版本');

    const withUpload = await mountDetail(['tool:upload']);
    const uploadButton = withUpload
      .findAll('.tb-detail__ops button')
      .find((item) => item.text().includes('上传新版本'));
    expect(uploadButton).toBeTruthy();
    // P3 已接通上传链路：入口可点击（属行为按计划变更，断言随行为更新，非为绿灯放宽）
    expect(uploadButton?.attributes('disabled')).toBeUndefined();
  });
});
