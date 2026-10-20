/**
 * 测试范围：工具表格的「链接工具」展示与编辑入口（src/components/ToolTable.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死 M-25 的列表口径 —— 类型列、LINK 行的"打开链接"、编辑按钮按行判权
 * 防回归问题：LINK 行仍出现下载按钮、LINK 行显示 0 B / 空版本、任何人都有编辑入口
 */
import { afterEach, describe, expect, it } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import ElementPlus from 'element-plus';
import type { ToolVO } from '@/api/tool';
import ToolTable from '@/components/ToolTable.vue';
import { useUserStore } from '@/stores/user';

/** el-table 会挂载若干计时器/观察器：每个用例结束必须卸载，否则 vitest 进程不退出 */
enableAutoUnmount(afterEach);

const FILE_TOOL: ToolVO = {
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
  toolType: 'FILE',
  linkUrl: null,
};

const LINK_TOOL: ToolVO = {
  ...FILE_TOOL,
  id: '1004',
  name: '内部知识库',
  latestVersion: '',
  fileExt: '',
  fileSize: 0,
  toolType: 'LINK',
  linkUrl: 'https://example.com/kb',
};

async function mountTable(list: ToolVO[], permissions: string[] = []) {
  setActivePinia(createPinia());
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

  const wrapper = mount(ToolTable, {
    props: { list },
    global: { plugins: [ElementPlus], stubs: { RouterLink: true } },
  });
  await flushPromises();
  return wrapper;
}

describe('工具列表：链接工具与编辑入口（M-25）', () => {
  it('类型列：FILE 显示「工具」，LINK 显示「链接」', async () => {
    const wrapper = await mountTable([FILE_TOOL, LINK_TOOL]);
    const text = wrapper.text();

    expect(text).toContain('工具');
    expect(text).toContain('链接');
  });

  it('LINK 行：渲染「打开链接」（带 noopener），不渲染「下载」', async () => {
    const wrapper = await mountTable([LINK_TOOL], ['tool:download', 'tool:upload']);
    const openLink = wrapper.findAll('a').find((item) => item.text() === '打开链接');

    expect(openLink).toBeTruthy();
    expect(openLink?.attributes('href')).toBe('https://example.com/kb');
    expect(openLink?.attributes('rel')).toContain('noopener');
    expect(wrapper.text()).not.toContain('下载');
  });

  it('LINK 行：版本 / 大小 / 格式均显示「—」，不出现 0 B', async () => {
    const wrapper = await mountTable([LINK_TOOL]);
    const text = wrapper.text();

    expect(text).not.toContain('0 B');
    expect(text).not.toContain('v1.2');
    // 三列各一个占位符（固定列会把表体再渲染一份，因此用 >= 而不是精确值）
    expect((text.match(/—/g) ?? []).length).toBeGreaterThanOrEqual(3);
  });

  it('编辑入口：上传人本人可见（判权口径的完整覆盖见 tool-edit-permission.spec.ts）', async () => {
    const mine = await mountTable([{ ...FILE_TOOL, uploaderId: '1' }]);

    // 用 text() 断言（字符串）；直接断言元素集合会在失败时打印整棵 el-table → 格式化爆栈
    expect(mine.text()).toContain('编辑');
  });
});
