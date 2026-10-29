/**
 * 测试范围：权限变更下线处理（src/utils/sessionKicked.ts，P9-T2 / M-21 / 契约 v1.12 §3.3）
 * 测试类型：单元 + 轻集成（jsdom；WebSocket 以桩替换）
 * 测试目的：锁死"权限变更"文案分支，以及下线后的状态清理口径
 * 防回归问题：权限变更沿用被踢文案、弹窗可被 X/遮罩/ESC 绕过、下线后未清登录态、
 *             消息与 4004 双触发弹两次窗、被踢文案被误改
 * 关键不变量：
 *   - `PERMISSION_CHANGED` → 标题「权限变更」、正文「账号发生异常，请重新登录」
 *   - `SESSION_REPLACED` → **维持既有被踢文案**（回归）
 *   - 弹窗**只有「知道了」**，`showCancelButton` / `showClose` / `closeOnClickModal` / `closeOnPressEscape` 均为 false
 *   - 确认后：断开连接 → 清登录态 → `replace` 回登录页
 *   - 消息与 `4004` 双触发 → **只弹一次**
 * 边界说明：不连真实后端；弹窗以 spy 替换；本文件自带桩，**不修改**既有 session-kicked.spec.ts
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { ws } from '@/api/ws';
import { useUserStore } from '@/stores/user';
import { routes } from '@/router';
import {
  FORCE_LOGOUT_MESSAGE,
  FORCE_LOGOUT_TITLE,
  SESSION_END_TEXT,
  handleSessionKicked,
} from '@/utils/sessionKicked';

/** 最小 WebSocket 桩（与既有 spec 同构，独立定义） */
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

function stubAlert() {
  return vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm' as never);
}

beforeEach(async () => {
  MockWebSocket.reset();
  vi.stubGlobal('WebSocket', MockWebSocket);
  pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({ history: createMemoryHistory(), routes });
  await router.push('/login');
  await router.isReady();
});

afterEach(() => {
  ws.disconnect();
  ws.onForceLogout(() => undefined);
  vi.unstubAllGlobals();
});

describe('文案分支（验收 A6 / C6）', () => {
  it('权限变更 → 标题「权限变更」、正文「账号发生异常，请重新登录」', async () => {
    const alertSpy = stubAlert();

    await handleSessionKicked(router, 'PERMISSION_CHANGED');

    const [message, title] = alertSpy.mock.calls[0] as [string, string];
    expect(title).toBe('权限变更');
    expect(message).toBe('账号发生异常，请重新登录');
  });

  it('被踢（缺省）→ 维持既有文案（回归，不得被误改）', async () => {
    const alertSpy = stubAlert();

    await handleSessionKicked(router);

    const [message, title] = alertSpy.mock.calls[0] as [string, string];
    expect(title).toBe(FORCE_LOGOUT_TITLE);
    expect(message).toBe(FORCE_LOGOUT_MESSAGE);
    expect(title).toBe(SESSION_END_TEXT.SESSION_REPLACED.title);
  });
});

describe('弹窗形态（验收 A4）', () => {
  it('只有「知道了」，且 X / 遮罩 / ESC 均不能关闭', async () => {
    const alertSpy = stubAlert();

    await handleSessionKicked(router, 'PERMISSION_CHANGED');

    const [, , options] = alertSpy.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(options.confirmButtonText).toBe('知道了');
    expect(options.showCancelButton).toBe(false);
    expect(options.showClose).toBe(false);
    expect(options.closeOnClickModal).toBe(false);
    expect(options.closeOnPressEscape).toBe(false);
  });
});

describe('状态清理（验收 A5）', () => {
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

    await handleSessionKicked(router, 'PERMISSION_CHANGED');
    await flushPromises();

    expect(disconnectSpy).toHaveBeenCalled();
    expect(store.token).toBe('');
    expect(store.user).toBeNull();
    expect(store.permissions).toEqual([]);
    expect(replaceSpy).toHaveBeenCalledWith({ name: 'login' });
  });
});

describe('触发源幂等（验收 A3 / C2 / C5）', () => {
  it('PERMISSION_CHANGED 消息与关闭码 4004 双触发 → 弹窗只出现一次', async () => {
    const alertSpy = stubAlert();
    ws.onForceLogout((reason) => {
      void handleSessionKicked(router, reason);
    });

    ws.connect('token-x');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateMessage({ type: 'PERMISSION_CHANGED' });
    socket.simulateClose(4004);
    await flushPromises();

    expect(alertSpy).toHaveBeenCalledTimes(1);
    const [, title] = alertSpy.mock.calls[0] as [string, string];
    expect(title).toBe('权限变更');
  });

  it('消息与关闭码只到其一 → 仍然弹窗（双保险）', async () => {
    const alertSpy = stubAlert();
    ws.onForceLogout((reason) => {
      void handleSessionKicked(router, reason);
    });

    ws.connect('token-y');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateClose(4004); // 只收到关闭码
    await flushPromises();

    expect(alertSpy).toHaveBeenCalledTimes(1);
    expect((alertSpy.mock.calls[0] as [string, string])[1]).toBe('权限变更');
  });
});
