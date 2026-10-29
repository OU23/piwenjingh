/**
 * 测试范围：定向授权变更即时通知（P16 / 契约 v1.17 §3.3）—— `api/ws.ts` + 工具列表 / 详情页接线
 * 测试类型：单元（WsClient，原生 WebSocket 以桩替换）+ 组件（jsdom）
 * 测试目的：锁死「`GRANT_CHANGED` → 刷新数据」链路，并证明它**不进下线链路**
 * 防回归问题：授权变更被当成"权限变更"弹窗要求重登；收到通知却不刷新；
 *   详情页失去授权后停留在空白页 / 报错页而不回列表；`PERMISSION_CHANGED` 行为被改动
 * 关键不变量：
 *   - `GRANT_CHANGED` → 触发 `onGrantChanged`，**不触发** `onForceLogout`（**不登出**）
 *   - `PERMISSION_CHANGED` 仍走 `onForceLogout`（**回归**，两条路径互不干扰）
 *   - 工具列表页收到通知 → **重新拉列表**，且**不发生路由跳转**
 *   - 详情页收到通知后：仍可见 → 重新拉详情、**不跳转**；已失权（403）→ **提示 + 回列表**
 * 边界说明：不连真实后端；不覆盖关闭码 4001/4002/4004 的既有用例（见 ws.spec.ts / ws-permission-changed.spec.ts）
 * 依赖与限制：`WebSocket` 由本文件自带桩替换；各 api 模块为桩
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { WsClient, ws } from '@/api/ws';
import { ForbiddenError } from '@/api/request';
import type { ToolDetailVO, ToolVO } from '@/api/tool';
import { routes } from '@/router';
import { useUserStore } from '@/stores/user';
import ToolListView from '@/views/tool/ToolListView.vue';
import ToolDetailView from '@/views/tool/ToolDetailView.vue';

const mocks = vi.hoisted(() => ({
  listTools: vi.fn(),
  listTags: vi.fn(),
  getToolDetail: vi.fn(),
  updateTool: vi.fn(),
  saveToolGrants: vi.fn(),
  batchDeleteTools: vi.fn(),
  deleteTool: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  listTools: mocks.listTools,
  getToolDetail: mocks.getToolDetail,
  updateTool: mocks.updateTool,
  saveToolGrants: mocks.saveToolGrants,
  batchDeleteTools: mocks.batchDeleteTools,
  deleteTool: mocks.deleteTool,
  toolDownloadUrl: (id: string) => `/api/tools/${id}/download`,
}));

vi.mock('@/api/tag', () => ({ listTags: mocks.listTags }));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: {
      success: mocks.success,
      error: mocks.error,
      info: mocks.info,
      warning: mocks.warning,
    },
    ElMessageBox: { confirm: vi.fn() },
  };
});

/** 最小 WebSocket 桩（独立定义，避免改动既有 ws 测试文件） */
class MockWebSocket {
  static instances: MockWebSocket[] = [];

  static reset(): void {
    MockWebSocket.instances = [];
  }

  static last(): MockWebSocket {
    const socket = MockWebSocket.instances[MockWebSocket.instances.length - 1];
    if (!socket) {
      throw new Error('尚未创建 WebSocket 实例');
    }
    return socket;
  }

  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  send(): void {}

  close(): void {
    this.readyState = 3;
  }

  simulateOpen(): void {
    this.readyState = 1;
    this.onopen?.();
  }

  simulateMessage(payload: unknown): void {
    this.onmessage?.({ data: typeof payload === 'string' ? payload : JSON.stringify(payload) });
  }
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
  uploaderId: '9',
  deptName: '研发部',
  fileSize: 2048,
  downloadCount: 7,
  updateTime: '2026-09-17 10:00:00',
  toolType: 'FILE',
  linkUrl: null,
};

const DETAIL: ToolDetailVO = {
  ...TOOL,
  readme: '说明',
  platform: 'Windows',
  dependency: '无',
  uploaderId: '1',
  deptId: '1',
  createTime: '2026-09-01 09:00:00',
  versions: [],
};

let pinia: Pinia;
let router: Router;

function signIn(): void {
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'admin',
      realName: '管理员',
      deptId: '1',
      deptName: '研发部',
      permissions: ['tool:download'],
    },
  });
}

/** 捕获页面注册进来的「授权变更」回调 */
function captureGrantHandler(): () => void {
  let captured: (() => void) | null = null;
  vi.spyOn(ws, 'onGrantChanged').mockImplementation((handler) => {
    if (handler) {
      captured = handler as () => void;
    }
  });
  return () => {
    if (!captured) {
      throw new Error('页面未注册 GRANT_CHANGED 回调');
    }
    captured();
  };
}

async function mountList(): Promise<VueWrapper> {
  signIn();
  await router.push('/tools');
  await router.isReady();
  const wrapper = mount(ToolListView, { global: { plugins: [pinia, router, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

async function mountDetail(): Promise<VueWrapper> {
  signIn();
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
  Object.values(mocks).forEach((mock) => mock.mockReset());

  mocks.listTags.mockResolvedValue([]);
  mocks.listTools.mockResolvedValue({ list: [TOOL], total: 1, pageNum: 1, pageSize: 20 });
  mocks.getToolDetail.mockResolvedValue(DETAIL);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

// ---------------- Part A：WsClient 消息分流（A1 / A2） ----------------

describe('GRANT_CHANGED 消息分流（P16 / A1 / A2）', () => {
  it('A1：收到 GRANT_CHANGED → 触发授权变更回调，**不触发下线**（不登出）', () => {
    vi.stubGlobal('WebSocket', MockWebSocket);
    MockWebSocket.reset();

    const client = new WsClient();
    const onGrant = vi.fn();
    const onLogout = vi.fn();
    client.onGrantChanged(onGrant);
    client.onForceLogout(onLogout);

    client.connect('t');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateMessage({ type: 'GRANT_CHANGED', data: { reason: 'GRANT_CHANGED' } });

    expect(onGrant).toHaveBeenCalledTimes(1);
    expect(onLogout).not.toHaveBeenCalled();
  });

  it('A2（回归）：PERMISSION_CHANGED 仍走下线回调，且不触发授权变更回调', () => {
    vi.stubGlobal('WebSocket', MockWebSocket);
    MockWebSocket.reset();

    const client = new WsClient();
    const onGrant = vi.fn();
    const onLogout = vi.fn();
    client.onGrantChanged(onGrant);
    client.onForceLogout(onLogout);

    client.connect('t');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateMessage({ type: 'PERMISSION_CHANGED' });

    expect(onLogout).toHaveBeenCalledWith('PERMISSION_CHANGED');
    expect(onGrant).not.toHaveBeenCalled();
  });
});

// ---------------- Part B：工具列表页（A3） ----------------

describe('工具列表页接线（P16 / A3）', () => {
  it('A3：收到通知 → 重新拉取列表，且不发生路由跳转', async () => {
    const trigger = captureGrantHandler();
    await mountList();
    const before = mocks.listTools.mock.calls.length;

    trigger();
    await flushPromises();

    expect(mocks.listTools.mock.calls.length).toBe(before + 1);
    expect(router.currentRoute.value.name).toBe('tool-list');
    expect(mocks.info).toHaveBeenCalled();
  });
});

// ---------------- Part C：工具详情页（A4 / A5） ----------------

describe('工具详情页接线（P16 / A4 / A5）', () => {
  it('A4：重新拉详情返回 403（已失权）→ 提示 + 返回工具列表', async () => {
    const trigger = captureGrantHandler();
    await mountDetail();

    mocks.getToolDetail.mockRejectedValueOnce(new ForbiddenError());
    trigger();
    await flushPromises();

    expect(mocks.warning).toHaveBeenCalled();
    expect(router.currentRoute.value.name).toBe('tool-list');
  });

  it('A5：仍可见 → 重新拉取详情，不跳转', async () => {
    const trigger = captureGrantHandler();
    await mountDetail();
    const before = mocks.getToolDetail.mock.calls.length;

    trigger();
    await flushPromises();

    expect(mocks.getToolDetail.mock.calls.length).toBe(before + 1);
    expect(router.currentRoute.value.name).toBe('tool-detail');
    expect(mocks.warning).not.toHaveBeenCalled();
  });
});
