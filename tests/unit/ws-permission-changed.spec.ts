/**
 * 测试范围：WebSocket 客户端 —— 权限变更事件（src/api/ws.ts，P9-T1 / M-21 / 契约 v1.12 §3.3）
 * 测试类型：单元（jsdom，原生 WebSocket 以桩替换）
 * 测试目的：锁死新增的 `PERMISSION_CHANGED` 消息与关闭码 `4004` 的处理，以及"来源"透传
 * 防回归问题：`4004` 被当成网络异常反复重连、权限变更不触发下线、双触发重复弹窗、
 *             未知 `type` 打断心跳、来源（reason）未透传给上层
 * 关键不变量：
 *   - `PERMISSION_CHANGED` 消息 → 上报下线，且 `reason = 'PERMISSION_CHANGED'`
 *   - `4004` → **不重连**且上报下线（reason 同上）
 *   - 消息与 `4004` 双触发 / 重复消息 → **只上报一次**
 *   - `FORCE_LOGOUT` / 关闭码 `4002` 的 `reason = 'SESSION_REPLACED'`（既有语义不变）
 *   - 未知 `type` → 忽略，不影响既有分支与心跳
 * 边界说明：不连真实后端；本文件自带桩，**不修改**既有 `ws.spec.ts`
 * 依赖与限制：`WebSocket` 由测试桩替换
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WsClient } from '@/api/ws';

/** 最小 WebSocket 桩（与 ws.spec.ts 同形态，独立定义以免改动既有文件） */
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

beforeEach(() => {
  MockWebSocket.reset();
  vi.stubGlobal('WebSocket', MockWebSocket);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('权限变更消息（验收 A1）', () => {
  it('收到 PERMISSION_CHANGED → 上报下线且来源为 PERMISSION_CHANGED', () => {
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateMessage({ type: 'PERMISSION_CHANGED' });

    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith('PERMISSION_CHANGED');
  });

  it('既有 FORCE_LOGOUT 的来源仍为 SESSION_REPLACED（回归，验收 C6）', () => {
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateMessage({ type: 'FORCE_LOGOUT' });

    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith('SESSION_REPLACED');
  });
});

describe('关闭码 4004（验收 A2 / C1）', () => {
  it('4004 → 不重连且上报下线（reason = PERMISSION_CHANGED）', () => {
    vi.useFakeTimers();
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateClose(4004);
    vi.advanceTimersByTime(120_000);

    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith('PERMISSION_CHANGED');
    // ★ 关键：不得进入重连分支（否则会反复弹窗）
    expect(MockWebSocket.instances).toHaveLength(1);
  });

  it('对照：4004 与"其它码 1006"行为不同（1006 才重连）', () => {
    vi.useFakeTimers();
    const client = new WsClient();

    client.connect('t');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateClose(1006);
    vi.advanceTimersByTime(1_000);

    expect(MockWebSocket.instances).toHaveLength(2);
  });

  it('4002（被踢）的来源为 SESSION_REPLACED（回归）', () => {
    vi.useFakeTimers();
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateClose(4002);
    vi.advanceTimersByTime(120_000);

    expect(handler).toHaveBeenCalledWith('SESSION_REPLACED');
  });
});

describe('幂等（验收 A3 / C5）', () => {
  it('消息 + 4004 双触发 → 只上报一次', () => {
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateMessage({ type: 'PERMISSION_CHANGED' });
    socket.simulateClose(4004);

    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith('PERMISSION_CHANGED');
  });

  it('重复 PERMISSION_CHANGED 消息 → 只上报一次', () => {
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateMessage({ type: 'PERMISSION_CHANGED' });
    socket.simulateMessage({ type: 'PERMISSION_CHANGED' });

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('重新 connect 后幂等标记复位（新连接可再次上报）', () => {
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t1');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateMessage({ type: 'PERMISSION_CHANGED' });

    client.connect('t2');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateMessage({ type: 'PERMISSION_CHANGED' });

    expect(handler).toHaveBeenCalledTimes(2);
  });
});

describe('健壮性（验收 C4）', () => {
  it('未知 type → 忽略，不触发下线也不影响心跳', () => {
    vi.useFakeTimers();
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateMessage({ type: 'SOMETHING_NEW_FROM_SERVER' });

    expect(handler).not.toHaveBeenCalled();

    vi.advanceTimersByTime(30_000);
    expect(socket.sent.some((item) => item.includes('PING'))).toBe(true);
  });

  it('非法 JSON → 忽略，不抛异常', () => {
    const client = new WsClient();
    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();

    expect(() => socket.simulateMessage('not-json')).not.toThrow();
  });
});
