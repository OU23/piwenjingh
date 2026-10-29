/**
 * 测试范围：被踢下线处理（src/utils/sessionKicked.ts 与 api/ws.ts 联动，P7-T4）
 * 测试类型：单元 + 轻集成（jsdom；WebSocket 以桩替换）
 * 测试目的：锁死"下线通知"的形态与下线后的状态清理口径
 * 防回归问题：弹窗可被 X/遮罩/ESC 绕过、下线后未清登录态、未用 replace 导致返回键回到已登录页、
 *             消息与 4002 双触发弹两次窗
 * 关键不变量：
 *   - 弹窗**只有「知道了」**，`showCancelButton` / `showClose` / `closeOnClickModal` / `closeOnPressEscape` 均为 false
 *   - 确认后：断开连接 → 清登录态（token / user / permissions）→ `replace` 回登录页
 *   - `FORCE_LOGOUT` 与 `4002` 双触发 → **只弹一次**
 * 边界说明：不连真实后端；弹窗以 spy 替换
 * 依赖与限制：`WebSocket` 由测试桩替换
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { ws } from '@/api/ws';
import { useUserStore } from '@/stores/user';
import { routes } from '@/router';
import { FORCE_LOGOUT_MESSAGE, FORCE_LOGOUT_TITLE, handleSessionKicked } from '@/utils/sessionKicked';

/** 最小 WebSocket 桩（与 ws.spec.ts 同构） */
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
  sent: string[] = [];
  closeArgs: { code?: number; reason?: string } | null = null;
  onopen: ((event?: unknown) => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  onerror: ((event?: unknown) => void) | null = null;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(code?: number, reason?: string): void {
    this.closeArgs = { code, reason };
    this.readyState = 3;
  }

  simulateOpen(): void {
    this.readyState = 1;
    this.onopen?.();
  }

  simulateMessage(payload: unknown): void {
    this.onmessage?.({ data: typeof payload === 'string' ? payload : JSON.stringify(payload) });
  }

  simulateClose(code: number): void {
    this.readyState = 3;
    this.onclose?.({ code });
  }
}

let pinia: Pinia;
let router: Router;

/** 替换下线通知弹窗（默认点「知道了」） */
function stubAlert() {
  return vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm' as never);
}

async function readyRouter(): Promise<void> {
  await router.push('/login');
  await router.isReady();
}

beforeEach(async () => {
  MockWebSocket.reset();
  vi.stubGlobal('WebSocket', MockWebSocket);
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  await readyRouter();
});

afterEach(() => {
  ws.disconnect();
  ws.onForceLogout(() => undefined);
  vi.unstubAllGlobals();
});

describe('下线弹窗形态（验收 A6 / A7）', () => {
  it('只有「知道了」，且 X / 遮罩 / ESC 均不能关闭', async () => {
    const alertSpy = stubAlert();

    await handleSessionKicked(router);

    expect(alertSpy).toHaveBeenCalledTimes(1);
    const [message, title, options] = alertSpy.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(message).toBe(FORCE_LOGOUT_MESSAGE);
    expect(title).toBe(FORCE_LOGOUT_TITLE);
    expect(options.confirmButtonText).toBe('知道了');
    expect(options.showCancelButton).toBe(false);
    expect(options.showClose).toBe(false);
    expect(options.closeOnClickModal).toBe(false);
    expect(options.closeOnPressEscape).toBe(false);
  });
});

describe('下线后的状态清理（验收 A8）', () => {
  it('确认后：断开连接 → 清登录态 → replace 回登录页', async () => {
    stubAlert();
    const disconnectSpy = vi.spyOn(ws, 'disconnect');
    const replaceSpy = vi.spyOn(router, 'replace');
    const store = useUserStore();
    store.setLogin({
      token: 'token-value',
      user: {
        userId: '1',
        username: 'zhangsan',
        realName: '张三',
        deptId: '1',
        deptName: '研发部',
        permissions: ['tool:download'],
      },
    });

    await handleSessionKicked(router);
    await flushPromises();

    expect(disconnectSpy).toHaveBeenCalled();
    expect(store.token).toBe('');
    expect(store.user).toBeNull();
    expect(store.permissions).toEqual([]);
    expect(store.isLoggedIn).toBe(false);
    expect(replaceSpy).toHaveBeenCalledWith({ name: 'login' });
    expect(router.currentRoute.value.name).toBe('login');
  });
});

describe('触发源幂等（验收 A9 / C8）', () => {
  it('FORCE_LOGOUT 消息与关闭码 4002 双触发 → 弹窗只出现一次', async () => {
    const alertSpy = stubAlert();
    ws.onForceLogout(() => {
      void handleSessionKicked(router);
    });

    ws.connect('token-x');
    const socket = MockWebSocket.last();
    socket.simulateOpen();

    socket.simulateMessage({ type: 'FORCE_LOGOUT' });
    socket.simulateClose(4002);
    await flushPromises();

    expect(alertSpy).toHaveBeenCalledTimes(1);
  });
});
