/**
 * 测试范围：前端隐藏「下载次数」（M-23 验收 A22）
 * 测试类型：组件 + 常量（jsdom）
 * 测试目的：锁死"该字段不再出现在任何界面，也不可排序"（依据：B13 后它实为**签发次数**，方案 §2.7）
 * 防回归问题：某处又把它渲染出来、排序下拉仍保留"能排序但看不到"的选项
 * 关键不变量：
 *   - 工具列表 / 版本列表 / 详情面板 **均不渲染** "下载次数"
 *   - 排序枚举中**不存在** `downloadCount`（连带排序下拉项一并消失）
 * 边界说明：后端字段仍返回、仍累加（不改契约字段、不改数据）
 * 依赖与限制：Element Plus 以插件注册；`router-link` 以桩替换；el-table 的行在首次 tick 后才渲染，故挂载后 flushPromises
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { RouterLinkStub, flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import type { Component, Plugin } from 'vue';
import { createPinia, setActivePinia } from 'pinia';
import ElementPlus from 'element-plus';
import ToolTable from '@/components/ToolTable.vue';
import ToolVersionTable from '@/components/ToolVersionTable.vue';
import ToolInfoPanel from '@/components/ToolInfoPanel.vue';
import { SORT_BY, SORT_BY_LABEL } from '@/constants/enums';
import type { ToolDetailVO, ToolVO, ToolVersionVO } from '@/api/tool';

const TOOL: ToolVO = {
  id: '1',
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
  updateTime: '2026-10-13 10:00:00',
};

const VERSION: ToolVersionVO = {
  id: 'v2',
  version: 'v1.2',
  fileName: 'tool-v1.2.zip',
  fileExt: 'zip',
  fileSize: 2048,
  uploaderName: '张三',
  uploadTime: '2026-10-13 10:00:00',
  downloadCount: 7,
  isLatest: true,
};

const DETAIL: ToolDetailVO = {
  ...TOOL,
  readme: '说明',
  platform: 'Windows',
  dependency: 'Python 3.11',
  uploaderId: '9',
  deptId: '1',
  createTime: '2026-10-01 09:00:00',
  versions: [VERSION],
};

const GLOBAL = {
  plugins: [ElementPlus as Plugin],
  stubs: { RouterLink: RouterLinkStub },
};

async function mountWith(component: Component, props: Record<string, unknown>): Promise<VueWrapper> {
  const wrapper = mount(component, { props, global: GLOBAL });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  setActivePinia(createPinia());
});

describe('隐藏「下载次数」', () => {
  it('工具列表不渲染该列', async () => {
    const wrapper = await mountWith(ToolTable, { list: [TOOL] });

    expect(wrapper.text()).toContain('数据清洗小工具');
    expect(wrapper.text()).not.toContain('下载次数');
  });

  it('版本列表不渲染该列', async () => {
    const wrapper = await mountWith(ToolVersionTable, { toolId: '1', versions: [VERSION] });

    expect(wrapper.text()).toContain('v1.2');
    expect(wrapper.text()).not.toContain('下载次数');
  });

  it('详情面板不渲染该项', async () => {
    const wrapper = await mountWith(ToolInfoPanel, { detail: DETAIL });

    expect(wrapper.text()).toContain('归属部门');
    expect(wrapper.text()).not.toContain('下载次数');
  });

  it('排序枚举中不存在 downloadCount（避免"能排序但看不到"）', () => {
    expect(Object.values(SORT_BY)).not.toContain('downloadCount');
    expect(SORT_BY_LABEL).not.toHaveProperty('downloadCount');
    expect(Object.keys(SORT_BY_LABEL)).toEqual(['createTime', 'name']);
  });
});
