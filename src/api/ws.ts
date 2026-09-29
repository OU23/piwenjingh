/**
 * WebSocket 客户端（契约 §3.3 实时通道 / 方案 §7.2）。
 *
 * 职责：维护与 `/ws` 的长连接，接收服务端 `FORCE_LOGOUT` / `PERMISSION_CHANGED` / `GRANT_CHANGED` 推送，
 * 并把「会话终结」事件（含来源）与「定向授权变更」事件分别交给上层处理。
 * **零新增依赖** —— 使用浏览器原生 `WebSocket`。
 *
 * 要点：
 * - 基址与请求层**同源推导**（`VITE_API_BASE_URL`），不写死；
 * - **单例 + 幂等**：重复 `connect()` 先关闭旧连接，避免连接泄漏；
 * - 应用层心跳：每 30s 发 `PING`；收到 `PING` 回 `PONG`；
 * - 关闭码分支：`4001` 令牌失效 / `4002` 会话被替代（被踢）/ **`4004` 权限变更（M-21）** → 一律**不重连**；
 *   其它 / 无码（含网络断开）→ **指数退避重连** 1s→2s→4s→8s→16s→30s（封顶）；
 * - 下线事件**幂等**：消息与 `4002` 双触发只上报一次；
 * - **`GRANT_CHANGED`（v1.17）走独立回调，绝不复用下线链路** —— 定向授权存于数据库、**查库判定**，
 *   刷新即生效，因此**只刷新数据、不登出、不关连接**；
 * - 页面 `beforeunload` 时以 `1000` 正常关闭。
 *
 * 安全（security.md）：令牌只出现在握手 URL 的 query 中，**不打印、不写日志**。
 */

/** 服务端关闭码（契约 §3.3） */
export const WS_CLOSE_CODE = {
  /** 令牌无效 / 过期 / 会话已失效 —— 不重连，静默回登录页 */
  TOKEN_INVALID: 4001,
  /** 会话被替代（被踢）—— 不重连，按被踢处理 */
  SESSION_REPLACED: 4002,
  /** 权限已变更、会话被终结（**M-21 / 契约 §3.3 v1.12**）—— 不重连，按"权限变更"提示后回登录页 */
  PERMISSION_CHANGED: 4004,
} as const;

/** 应用层心跳间隔 */
export const HEARTBEAT_INTERVAL_MS = 30_000;

/** 重连退避阶梯（毫秒，末项封顶） */
export const BACKOFF_STEPS_MS = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000] as const;

/** 服务端消息体（契约 §3.3：`{ type, data?, timestamp }`） */
interface WsMessage {
  type: string;
  data?: Record<string, unknown>;
  timestamp?: string;
}

/** 下线来源（决定上层弹窗文案；M-21 新增 `PERMISSION_CHANGED`） */
export type SessionEndReason = 'SESSION_REPLACED' | 'PERMISSION_CHANGED';

/**
 * 下线回调。`reason` 为**可选**参数 —— 既有调用方（`() => void`）保持兼容。
 */
export type ForceLogoutHandler = (reason?: SessionEndReason) => void;

/**
 * 定向授权变更回调（**v1.17**）：服务端推送 `GRANT_CHANGED` 时触发，上层应**重新拉取工具数据**。
 * ⚠️ 与下线回调**完全独立** —— 不登出、不清 token、不跳登录页。
 */
export type GrantChangedHandler = () => void;

/** WebSocket OPEN 状态码（不依赖 mock 提供静态常量） */
const SOCKET_OPEN = 1;

/**
 * WS 基址：绝对地址按 `http → ws` / `https → wss` 转换；相对或空则与**页面同源**。
 * 与请求层 `baseURL` 同源策略保持一致。
 */
export function resolveWsBaseUrl(base: string): string {
  if (/^https?:\/\//i.test(base)) {
    return base.replace(/\/+$/, '').replace(/^http/i, 'ws');
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}`;
}

export class WsClient {
  private socket: WebSocket | null = null;
  private token = '';
  private reconnectAttempts = 0;
  private reconnectTimer: number | null = null;
  private heartbeatTimer: number | null = null;
  /** 下线事件幂等标记：消息与 4002 双触发只上报一次 */
  private forceLogoutHandled = false;
  private unloadBound = false;
  private handler: ForceLogoutHandler | null = null;
  /** 定向授权变更回调（v1.17）：与下线链路独立 */
  private grantHandler: GrantChangedHandler | null = null;

  /** 页面卸载时正常关闭（避免服务端残留连接） */
  private readonly handleUnload = (): void => {
    this.closeSocket(1000, 'page unload');
  };

  /** 注册"被踢下线"回调（由上层接线） */
  onForceLogout(handler: ForceLogoutHandler): void {
    this.handler = handler;
  }

  /**
   * 注册「定向授权变更」回调（v1.17，由页面接线）。
   * 页面卸载时传 `null` **取消注册**（避免已销毁页面被回调）。
   */
  onGrantChanged(handler: GrantChangedHandler | null): void {
    this.grantHandler = handler;
  }

  /** 建立连接（幂等：重复调用先关闭旧连接；无 token 不建连） */
  connect(token: string): void {
    if (!token) {
      return;
    }
    this.closeSocket();
    this.clearTimers();
    this.token = token;
    this.reconnectAttempts = 0;
    this.forceLogoutHandled = false;
    this.bindUnload();
    this.open();
  }

  /** 主动断开（登出 / 被踢 / 令牌失效）；断开后不再重连 */
  disconnect(): void {
    this.token = '';
    this.clearTimers();
    this.closeSocket();
    this.unbindUnload();
  }

  /** 当前是否处于已连接状态（供上层判断，不暴露连接细节） */
  isConnected(): boolean {
    return this.socket !== null && this.socket.readyState === SOCKET_OPEN;
  }

  // ---------------- 内部 ----------------

  private open(): void {
    const base = resolveWsBaseUrl((import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '');
    const url = `${base}/ws?token=${encodeURIComponent(this.token)}`;
    const socket = new WebSocket(url);
    this.socket = socket;

    socket.onopen = () => {
      // 重连成功：重置退避计数
      this.reconnectAttempts = 0;
      this.startHeartbeat();
    };
    socket.onmessage = (event: MessageEvent) => {
      this.handleMessage(event.data);
    };
    socket.onclose = (event: CloseEvent) => {
      this.handleClose(event);
    };
    socket.onerror = () => {
      // 错误细节不重要：真正的分支处理统一交给 onclose（方案 §7.2）
    };
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== 'string') {
      return;
    }
    let message: WsMessage;
    try {
      message = JSON.parse(raw) as WsMessage;
    } catch {
      return;
    }
    if (message.type === 'PING') {
      this.send({ type: 'PONG' });
      return;
    }
    if (message.type === 'FORCE_LOGOUT') {
      this.emitForceLogout('SESSION_REPLACED');
      return;
    }
    if (message.type === 'PERMISSION_CHANGED') {
      // 权限变更（M-21 / 契约 §3.3 v1.12）：与"被踢"同一上报路径，文案由上层按来源区分
      this.emitForceLogout('PERMISSION_CHANGED');
      return;
    }
    if (message.type === 'GRANT_CHANGED') {
      // 定向授权变更（v1.17 / 契约 §3.3）：**走独立回调** —— 只让上层刷新数据，
      // 绝不进入下线链路（授权是查库判定，刷新即生效，无需重登）
      this.grantHandler?.();
    }
  }

  private handleClose(event: CloseEvent): void {
    this.clearTimers();
    this.socket = null;

    if (event.code === WS_CLOSE_CODE.TOKEN_INVALID) {
      return; // 4001：不重连（静默下线）
    }
    if (event.code === WS_CLOSE_CODE.SESSION_REPLACED) {
      this.emitForceLogout('SESSION_REPLACED'); // 4002：不重连，按被踢处理
      return;
    }
    if (event.code === WS_CLOSE_CODE.PERMISSION_CHANGED) {
      // 4004：权限变更（M-21）—— 不重连，按"权限变更"处理
      this.emitForceLogout('PERMISSION_CHANGED');
      return;
    }
    if (!this.token) {
      return; // 已主动断开
    }
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    const step = BACKOFF_STEPS_MS[Math.min(this.reconnectAttempts, BACKOFF_STEPS_MS.length - 1)];
    this.reconnectAttempts += 1;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, step);
  }

  private startHeartbeat(): void {
    this.clearHeartbeat();
    this.heartbeatTimer = window.setInterval(() => {
      this.send({ type: 'PING' });
    }, HEARTBEAT_INTERVAL_MS);
  }

  private send(payload: WsMessage): void {
    if (this.socket !== null && this.socket.readyState === SOCKET_OPEN) {
      this.socket.send(JSON.stringify(payload));
    }
  }

  /** 下线事件幂等上报（消息与关闭码双触发只上报一次） */
  private emitForceLogout(reason: SessionEndReason): void {
    if (this.forceLogoutHandled) {
      return;
    }
    this.forceLogoutHandled = true;
    this.handler?.(reason);
  }

  private closeSocket(code?: number, reason?: string): void {
    const socket = this.socket;
    this.socket = null;
    if (socket !== null) {
      try {
        socket.close(code, reason);
      } catch {
        // 关闭失败不阻断流程
      }
    }
  }

  private clearTimers(): void {
    this.clearHeartbeat();
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private clearHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      window.clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private bindUnload(): void {
    if (!this.unloadBound) {
      window.addEventListener('beforeunload', this.handleUnload);
      this.unloadBound = true;
    }
  }

  private unbindUnload(): void {
    if (this.unloadBound) {
      window.removeEventListener('beforeunload', this.handleUnload);
      this.unloadBound = false;
    }
  }
}

/** 全局单例：登录成功后 `connect()`，登出 / 被踢 / 401 时 `disconnect()` */
export const ws = new WsClient();
