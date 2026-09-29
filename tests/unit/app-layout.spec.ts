/**
 * 测试范围：全站布局骨架（src/components/AppLayout.vue）——按权限点渲染导航、顶栏用户信息
 * 测试类型：组件（jsdom）
 * 测试目的：保证"无权限的菜单与入口不渲染"在真实组件里成立
 * 防回归问题：导航写死全部菜单、顶栏不显示当前用户与归属部门
 * 关键不变量：
 *   - 无 tool:upload 时导航不含"上传工具"
 *   - 无 user:manage 时导航不含"用户管理"及其分组
 *   - 顶栏显示当前用户姓名与归属部门
 * 边界说明：不验证样式与真实接口（页面本体在 P2–P4 实现）
 * 依赖与限制：jsdom 环境；路由用内存历史；Element Plus 以插件方式注册
 *   — P2 起「工具列表」已是真实数据页：布局会连带渲染它，故对接口做隔离，
 *     避免本用例依赖后端是否存在（属测试隔离，未改动任何既有断言）
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import AppLayout from '@/components/AppLayout.vue';
import { PERMISSION } from '@/constants/permissions';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';

const pageMocks = vi.hoisted(() => ({ listTools: vi.fn(), listTags: vi.fn() }));

vi.mock('@/api/tool', () => ({
  listTools: pageMocks.listTools,
  toolDownloadUrl: (id: string) => `/api/tools/${id}/download`,
}));

vi.mock('@/api/tag', () => ({
  listTags: pageMocks.listTags,
}));

let pinia: Pinia;
let router: Router;

async function mountLayout(permissions: string[]) {
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

  const wrapper = mount(AppLayout, {
    global: { plugins: [pinia, router, ElementPlus] },
  });
  await wrapper.vm.$nextTick();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  pageMocks.listTools.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 20 });
  pageMocks.listTags.mockResolvedValue([]);
});

describe('布局骨架', () => {
  it('普通用户只看到登录即可见的入口', async () => {
    const wrapper = await mountLayout([]);
    const text = wrapper.text();

    expect(text).toContain('工具列表');
    expect(text).not.toContain('上传工具');
    expect(text).not.toContain('已删除工具');
    expect(text).not.toContain('用户管理');
    expect(text).not.toContain('系统管理');
  });

  it('按权限点显示对应入口', async () => {
    // ⚠️ M-21 口径变更（契约 v1.12）：`sys:setting` = 全局管理员 → **不受权限点限制**。
    //    故本用例不再用 `sys:setting` 作普通权限点，改用普通组合继续验证
    //    "有权限显示 / 无权限不显示"。**断言未削弱，仅夹具对齐**。
    const wrapper = await mountLayout([PERMISSION.TOOL_UPLOAD, PERMISSION.USER_MANAGE, PERMISSION.DEPT_MANAGE]);
    const text = wrapper.text();

    expect(text).toContain('上传工具');
    expect(text).toContain('用户管理');
    expect(text).toContain('部门管理');
    expect(text).not.toContain('全局设置'); // 未持有 sys:setting
    expect(text).not.toContain('已删除工具'); // 未持有 tool:delete
  });

  it('顶栏显示当前用户与归属部门', async () => {
    const wrapper = await mountLayout([]);

    expect(wrapper.text()).toContain('张三');
    expect(wrapper.text()).toContain('研发部');
  });
});
