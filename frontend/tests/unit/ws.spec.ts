/**
 * 测试范围：WebSocket 客户端（src/api/ws.ts，P7-T1）
 * 测试类型：单元（jsdom，原生 WebSocket 以桩替换）
 * 测试目的：锁死连接形态、关闭码分支、重连退避与"被踢"事件的幂等
 * 防回归问题：URL 缺 token、4001/4002 被当成网络异常反复重连、退避不封顶、
 *             消息与 4002 双触发导致重复弹窗
 * 关键不变量：
 *   - 连接 URL 含 `?token=`（编码）；**无 token 不建连**
 *   - 重复 `connect()` 关闭旧连接（单例）
 *   - `4001` 不重连且不触发下线；`4002` 不重连且触发下线；其它/无码重连
 *   - 退避 1s→2s→4s→8s→16s→30s 封顶，重连成功后重置
 *   - `FORCE_LOGOUT` 与 `4002` 双触发只上报一次
 *   - `beforeunload` 以 1000 正常关闭
 * 边界说明：不连真实后端；心跳只验证"会发 PING / 会回 PONG"
 * 依赖与限制：`WebSocket` 由测试桩替换
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BACKOFF_STEPS_MS, HEARTBEAT_INTERVAL_MS, WsClient, resolveWsBaseUrl } from '@/api/ws';

/** 最小 WebSocket 桩：记录 URL / 发送内容 / 关闭参数，并提供状态迁移辅助方法 */
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

  // ---------------- 测试辅助 ----------------

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

describe('连接形态（验收 A5）', () => {
  it('连接 URL 含 ?token=（已 URL 编码）', () => {
    const client = new WsClient();
    client.connect('abc def');

    expect(MockWebSocket.instances).toHaveLength(1);
    expect(MockWebSocket.last().url).toContain('/ws?token=abc%20def');
    client.disconnect();
  });

  it('无 token 时不建立连接', () => {
    const client = new WsClient();

    client.connect('');

    expect(MockWebSocket.instances).toHaveLength(0);
  });

  it('基址：绝对地址 http→ws / https→wss；相对或空则同源', () => {
    expect(resolveWsBaseUrl('http://10.0.0.5:8080')).toBe('ws://10.0.0.5:8080');
    expect(resolveWsBaseUrl('https://tool.example.com/')).toBe('wss://tool.example.com');
    expect(resolveWsBaseUrl('')).toBe(`ws://${window.location.host}`);
  });
});

describe('单例与幂等（验收 A12）', () => {
  it('重复 connect 先关闭旧连接，不产生并存连接', () => {
    const client = new WsClient();

    client.connect('t1');
    const first = MockWebSocket.last();
    client.connect('t2');

    expect(MockWebSocket.instances).toHaveLength(2);
    expect(first.closeArgs).not.toBeNull();
    expect(MockWebSocket.last().url).toContain('token=t2');
    client.disconnect();
  });
});

describe('关闭码分支（验收 A10 / C6）', () => {
  it('4001（令牌失效）→ 不重连、不触发下线', () => {
    vi.useFakeTimers();
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateClose(4001);
    vi.advanceTimersByTime(120_000);

    expect(handler).not.toHaveBeenCalled();
    expect(MockWebSocket.instances).toHaveLength(1);
  });

  it('4002（会话被替代）→ 不重连且触发下线', () => {
    vi.useFakeTimers();
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateClose(4002);
    vi.advanceTimersByTime(120_000);

    expect(handler).toHaveBeenCalledTimes(1);
    expect(MockWebSocket.instances).toHaveLength(1);
  });

  it('其它 / 无码（网络异常）→ 触发重连', () => {
    vi.useFakeTimers();
    const client = new WsClient();

    client.connect('t');
    MockWebSocket.last().simulateOpen();
    MockWebSocket.last().simulateClose(1006);

    vi.advanceTimersByTime(BACKOFF_STEPS_MS[0]);
    expect(MockWebSocket.instances).toHaveLength(2);
  });

  it('主动 disconnect 后不再重连', () => {
    vi.useFakeTimers();
    const client = new WsClient();

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    client.disconnect();
    socket.simulateClose(1006);
    vi.advanceTimersByTime(120_000);

    expect(MockWebSocket.instances).toHaveLength(1);
  });
});

describe('重连退避（验收 A11）', () => {
  it('间隔按 1s→2s→4s→8s→16s→30s 递增并在 30s 封顶', () => {
    vi.useFakeTimers();
    const client = new WsClient();

    client.connect('t');

    const delays = [...BACKOFF_STEPS_MS, BACKOFF_STEPS_MS[BACKOFF_STEPS_MS.length - 1]];
    delays.forEach((delay, index) => {
      const before = MockWebSocket.instances.length;
      MockWebSocket.last().simulateClose(1006);

      // 差 1ms 不触发，恰好到点才触发
      vi.advanceTimersByTime(delay - 1);
      expect(MockWebSocket.instances, `第 ${index + 1} 次退避：未到点不得重连`).toHaveLength(before);

      vi.advanceTimersByTime(1);
      expect(MockWebSocket.instances, `第 ${index + 1} 次退避：到点应重连`).toHaveLength(before + 1);
    });
  });

  it('重连成功后计数重置（回到 1s）', () => {
    vi.useFakeTimers();
    const client = new WsClient();

    client.connect('t');
    MockWebSocket.last().simulateClose(1006);
    vi.advanceTimersByTime(BACKOFF_STEPS_MS[0]);
    MockWebSocket.last().simulateOpen(); // 重连成功 → 重置

    MockWebSocket.last().simulateClose(1006);
    vi.advanceTimersByTime(BACKOFF_STEPS_MS[0]);
    expect(MockWebSocket.instances).toHaveLength(3);
  });
});

describe('心跳（方案 §7.2）', () => {
  it('连接建立后每 30s 发 PING；收到 PING 回 PONG', () => {
    vi.useFakeTimers();
    const client = new WsClient();

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();

    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS);
    expect(socket.sent.some((item) => item.includes('PING'))).toBe(true);

    socket.simulateMessage({ type: 'PING' });
    expect(socket.sent.some((item) => item.includes('PONG'))).toBe(true);
  });
});

describe('被踢事件幂等（验收 A9）', () => {
  it('FORCE_LOGOUT 消息与 4002 双触发 → 只上报一次', () => {
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateMessage({ type: 'FORCE_LOGOUT' });
    socket.simulateClose(4002);

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('重复 FORCE_LOGOUT 消息 → 只上报一次', () => {
    const client = new WsClient();
    const handler = vi.fn();
    client.onForceLogout(handler);

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    socket.simulateMessage({ type: 'FORCE_LOGOUT' });
    socket.simulateMessage({ type: 'FORCE_LOGOUT' });

    expect(handler).toHaveBeenCalledTimes(1);
  });
});

describe('页面卸载（验收 C10）', () => {
  it('beforeunload → 以 1000 正常关闭连接', () => {
    const client = new WsClient();

    client.connect('t');
    const socket = MockWebSocket.last();
    socket.simulateOpen();
    window.dispatchEvent(new Event('beforeunload'));

    expect(socket.closeArgs).toEqual({ code: 1000, reason: 'page unload' });
    client.disconnect();
  });
});
