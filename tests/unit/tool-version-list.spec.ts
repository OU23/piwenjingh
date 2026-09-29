/**
 * 测试范围：工具「版本列表」组件（src/components/ToolVersionTable.vue）—— **组件层**测试
 * ⚠️ 当前详情页的展示开关（ToolDetailView 的 SHOW_VERSION_LIST）为 **false**（版本列表暂不展示）；
 *    本文件**不依赖该开关**，验证的是组件自身行为，故开关关闭时仍应全绿、恢复时无需改动。
 * 测试类型：组件（jsdom）
 * 测试目的：锁死「同名转版本」下历史版本的展示，以及**按版本下载**入口的显隐与参数
 * 防回归问题：版本行缺少下载入口（用户只能下最新版）、无下载权限仍渲染下载入口、
 *   版本顺序被前端重排（应信任接口的上传时间倒序）、单版本工具渲染异常
 * 关键不变量：
 *   - 版本按**传入顺序**渲染（接口已按上传时间倒序），仅 isLatest 行带「最新」标记
 *   - 有 tool:download → 每行 1 个下载入口，且**携带该版本 id**；无该权限 → 不渲染
 *   - 单版本工具同样正常渲染
 * 边界说明：不覆盖真实下载传输（下载核心另有测试）；详情页整体见 tool-detail.spec.ts
 * 依赖与限制：下载按钮以桩替换（只验证入口与参数，不涉及下载实现）；Element Plus 以插件注册；
 *   el-table 的行在首次 tick 后才渲染，故挂载后统一 flushPromises
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import ElementPlus from 'element-plus';
import type { ToolVersionVO } from '@/api/tool';
import { useUserStore } from '@/stores/user';
import ToolVersionTable from '@/components/ToolVersionTable.vue';

const V1_2: ToolVersionVO = {
  id: 'v2',
  version: 'v1.2',
  fileName: 'tool-v1.2.zip',
  fileExt: 'zip',
  fileSize: 2048,
  uploaderName: '张三',
  uploadTime: '2026-09-17 10:00:00',
  downloadCount: 7,
  isLatest: true,
};

const V1_0: ToolVersionVO = {
  id: 'v1',
  version: 'v1.0',
  fileName: 'tool-v1.0.zip',
  fileExt: 'zip',
  fileSize: 1024,
  uploaderName: '张三',
  uploadTime: '2026-09-01 09:00:00',
  downloadCount: 2,
  isLatest: false,
};

let pinia: Pinia;

async function mountTable(versions: ToolVersionVO[], permissions: string[] = []): Promise<VueWrapper> {
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

  const wrapper = mount(ToolVersionTable, {
    props: { toolId: '1001', versions },
    global: {
      plugins: [pinia, ElementPlus],
      // 下载按钮以桩替换：只验证"入口是否渲染 + 携带哪个版本"
      stubs: {
        DownloadButton: {
          name: 'DownloadButton',
          props: ['toolId', 'versionId', 'suggestedName'],
          template: '<span class="stub-download" />',
        },
      },
    },
  });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
});

describe('工具版本列表（P17 恢复展示）', () => {
  it('按传入顺序渲染版本，仅最新版带「最新」标记（A2）', async () => {
    const wrapper = await mountTable([V1_2, V1_0]);

    expect(wrapper.findAll('.tb-ver__no').map((el) => el.text())).toEqual(['v1.2', 'v1.0']);
    expect(wrapper.findAll('.tb-ver__latest').length).toBe(1);
    expect(wrapper.findAll('.tb-ver__latest')[0]?.text()).toContain('最新');
  });

  it('每行展示版本号、文件名、上传人与下载次数（A3）', async () => {
    const wrapper = await mountTable([V1_2, V1_0]);
    const text = wrapper.text();

    expect(text).toContain('v1.2');
    expect(text).toContain('tool-v1.0.zip');
    expect(text).toContain('张三');
    expect(text).toContain('2.0 KB');
  });

  it('有 tool:download → 每个版本各有一个下载入口，且**携带该版本 id**（A4）', async () => {
    const wrapper = await mountTable([V1_2, V1_0], ['tool:download']);

    const entries = wrapper.findAllComponents({ name: 'DownloadButton' });
    expect(entries.length).toBe(2);
    expect(entries.map((c) => c.props('versionId'))).toEqual(['v2', 'v1']);
  });

  it('无 tool:download → 不渲染任何下载入口（A4 负向）', async () => {
    const wrapper = await mountTable([V1_2, V1_0], []);
    expect(wrapper.findAllComponents({ name: 'DownloadButton' }).length).toBe(0);
  });

  it('单版本工具：正常渲染一行，且标记为最新、有下载入口（A5）', async () => {
    const wrapper = await mountTable([V1_2], ['tool:download']);

    expect(wrapper.findAll('.tb-ver__no').map((el) => el.text())).toEqual(['v1.2']);
    expect(wrapper.findAll('.tb-ver__latest').length).toBe(1);
    expect(wrapper.findAllComponents({ name: 'DownloadButton' }).length).toBe(1);
  });
});
