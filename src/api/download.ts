import { BizError, ForbiddenError, UnauthorizedError, parseFileName } from './request';
import type { SaveTarget } from './saveTarget';
import { BizCode } from './types';

/**
 * 鉴权下载的传输层（契约 §9.5）
 *
 * 三条硬约束（principles.md R4 / R5）：
 * 1. **不得用静态直链**：文件只能带令牌经接口取，禁止 `<a href>` 指向下载地址；
 *    受控例外：方案 2-B（`docs/内部-大文件下载方案.md`）在非安全上下文下用短时效 `?token=`
 *    拼进下载地址后由原生 `<a>` 触发——仍是令牌鉴权、非静态直链，故不违背本条。
 * 2. **大文件必须流式**：边收边写盘，不得先把整个文件读进内存；
 * 3. **支持续传**：中断后用 `Range` 从已写位置继续，不从头再来。
 *
 * 流式为默认路径（保存目标见 src/api/saveTarget.ts）；非安全上下文（HTTP / IP 访问）下
 * File System Access API 不可用，走整包降级路径：带令牌 fetch 读入后交浏览器落盘到默认下载目录，
 * 仍可显示进度；**不限制文件大小**（任意大小都可下载）。
 */

export interface DownloadProgress {
  loaded: number;
  total: number | null;
  /** 总量未知时为 null，界面只显示已下载量 */
  percent: number | null;
}

export interface DownloadOutcome {
  /** false = 被中断，可按 written 续传 */
  done: boolean;
  written: number;
  total: number | null;
}

/** 解析文件总大小：优先 `Content-Range: bytes a-b/total`，其次 `Content-Length`（加上已写偏移） */
export function parseTotalSize(contentRange: string | null, contentLength: string | null, offset = 0): number | null {
  if (contentRange) {
    const matched = /\/(\d+)\s*$/.exec(contentRange);
    if (matched?.[1]) {
      return Number(matched[1]);
    }
  }
  if (contentLength) {
    const length = Number(contentLength);
    if (Number.isFinite(length) && length >= 0) {
      return length + offset;
    }
  }
  return null;
}

export function percentOf(loaded: number, total: number | null): number | null {
  if (total === null || total <= 0) {
    return null;
  }
  return Math.min(100, Math.round((loaded / total) * 1000) / 10);
}

/** 失败响应 → 统一错误（401 / 403 与业务码分开，便于界面给出可理解提示） */
async function toDownloadError(response: Response): Promise<Error> {
  let message = `下载失败（HTTP ${response.status}）`;
  let code: number | undefined;

  try {
    const body = (await response.json()) as { code?: number; message?: string };
    if (body?.message) {
      message = body.message;
    }
    code = body?.code;
  } catch {
    // 非 JSON 响应体：沿用默认文案
  }

  if (response.status === 401) {
    return new UnauthorizedError(message);
  }
  if (response.status === 403) {
    return new ForbiddenError(message);
  }
  return new BizError(code ?? BizCode.SYSTEM_ERROR, message);
}

function authHeaders(token: string, extra?: Record<string, string>): Record<string, string> {
  return { ...(extra ?? {}), Authorization: `Bearer ${token}` };
}

/**
 * 失败判定：下载失败时后端回 JSON（401 / 403 用真状态码，1004 / 2004 为 HTTP 200 + 业务码）
 * 因此成功分支也必须排除 JSON 响应，否则会把错误提示当成文件写进磁盘
 */
function isJsonResponse(response: Response): boolean {
  return (response.headers.get('Content-Type') ?? '').toLowerCase().includes('application/json');
}

async function ensureFileResponse(response: Response): Promise<void> {
  if (!response.ok || isJsonResponse(response)) {
    throw await toDownloadError(response);
  }
}

/**
 * 流式下载到本地文件；被中断/取消时返回 `done = false` 并保留已写字节数（可续传）
 */
export async function streamToTarget(options: {
  url: string;
  token: string;
  target: SaveTarget;
  offset?: number;
  onProgress?: (progress: DownloadProgress) => void;
  signal: AbortSignal;
}): Promise<DownloadOutcome> {
  const { url, token, target, signal, onProgress } = options;
  let written = options.offset ?? 0;

  const response = await fetch(url, {
    headers: authHeaders(token, written > 0 ? { Range: `bytes=${written}-` } : undefined),
    signal,
  });

  await ensureFileResponse(response);

  // 服务端未按 Range 响应（200 而非 206）→ 只能从头写，已写内容作废
  const resuming = written > 0 && response.status === 206;
  if (written > 0 && !resuming) {
    written = 0;
  }

  const total = parseTotalSize(
    response.headers.get('Content-Range'),
    response.headers.get('Content-Length'),
    resuming ? written : 0,
  );

  const writer = await target.open();
  onProgress?.({ loaded: written, total, percent: percentOf(written, total) });

  if (written === 0 && total !== null) {
    await writer.truncate(total).catch(() => undefined);
  }
  await writer.seek(written);

  let interrupted = false;

  try {
    if (response.body) {
      const reader = response.body.getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        if (value && value.byteLength > 0) {
          await writer.write(value);
          written += value.byteLength;
          onProgress?.({ loaded: written, total, percent: percentOf(written, total) });
        }
      }
    } else {
      // 无流式读取能力（极少数环境）：一次性读完，仍写同一目标
      const buffer = new Uint8Array(await response.arrayBuffer());
      await writer.write(buffer);
      written += buffer.byteLength;
      onProgress?.({ loaded: written, total, percent: percentOf(written, total) });
    }
  } catch (error) {
    if (signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
      interrupted = true;
    } else {
      await writer.abort().catch(() => undefined);
      throw error;
    }
  }

  if (interrupted) {
    // 主动中断：先落盘已写内容，保留目标与偏移量供续传（abort 会丢弃已写数据）
    await writer.close().catch(() => undefined);
    return { done: false, written, total };
  }

  await writer.close();
  return { done: true, written, total };
}

/**
 * HEAD 探测失败时，再用 GET 取一次**错误详情**。
 *
 * HEAD 响应没有 body（框架/协议都不发送），前端读不到后端的 `{ code, message }`，
 * 只能回落到"下载失败（HTTP xxx）"。这里补一次 GET，把真正的业务原因取出来。
 * - 带 `Range: bytes=1-1`：起点 > 0 按契约 §9.5 / N-02 **不累计下载次数**，也不会拉整包；
 * - 若意外拿到的是文件流（HEAD 与 GET 判定不一致），立即 `cancel()` 取消，不下载整包；
 * - 任何异常都吞掉返回 null，调用方回落到 HEAD 的通用错误（不因探测失败影响主流程）。
 */
async function readFailureDetail(url: string, token: string, signal: AbortSignal): Promise<Error | null> {
  try {
    const detail = await fetch(url, {
      headers: authHeaders(token, { Range: 'bytes=1-1' }),
      signal,
    });

    if (isJsonResponse(detail)) {
      return await toDownloadError(detail);
    }

    await detail.body?.cancel().catch(() => undefined);
    return null;
  } catch {
    return null;
  }
}

/**
 * 探测文件总大小（用于判断降级路径是否可用）
 *
 * 用 **HEAD** 而非 `Range: bytes=0-0`：HEAD 不返回响应体，且服务端**不计数**
 * （契约 §9.5 + `接口补充说明.md` N-02），避免探测请求污染下载次数。
 * 探测失败时（HEAD 无响应体、读不到原因）再用 GET 取一次错误详情，提示后端真正的 message。
 */
export async function probeTotalSize(options: { url: string; token: string; signal: AbortSignal }): Promise<number | null> {
  const response = await fetch(options.url, {
    method: 'HEAD',
    headers: authHeaders(options.token),
    signal: options.signal,
  });

  // 失败判定：非 2xx，或后端以 JSON 回绝（1004 / 2004 为 HTTP 200 + 业务码）
  if (!response.ok || isJsonResponse(response)) {
    const detailError = await readFailureDetail(options.url, options.token, options.signal);
    throw detailError ?? (await toDownloadError(response));
  }

  // HEAD 无响应体：优先 Content-Range，其次 Content-Length（此时 offset 为 0）
  return parseTotalSize(response.headers.get('Content-Range'), response.headers.get('Content-Length'), 0);
}

/**
 * 降级路径：整包读取并落盘到浏览器默认下载目录（非安全上下文 / 不支持流式写盘时）。
 *
 * 该路径仍走**带令牌的接口**（符合「禁止静态直链」原则），故 HTTP / IP 直接访问也能鉴权下载；
 * 与流式路径的区别是文件会先整包读入浏览器内存再保存，因此受内存约束（几百 MB ~ 1-2 GB 可用，
 * 超大文件建议改安全上下文或后端 token 下载）。读取过程逐片累加并回报进度。
 */
export async function fetchAsBlob(options: {
  url: string;
  token: string;
  signal: AbortSignal;
  onProgress?: (progress: DownloadProgress) => void;
}): Promise<{ blob: Blob; fileName: string | null }> {
  const response = await fetch(options.url, {
    headers: authHeaders(options.token),
    signal: options.signal,
  });

  await ensureFileResponse(response);

  const total = parseTotalSize(
    response.headers.get('Content-Range'),
    response.headers.get('Content-Length'),
    0,
  );

  const chunks: Uint8Array[] = [];
  let loaded = 0;
  options.onProgress?.({ loaded: 0, total, percent: percentOf(0, total) });

  if (response.body) {
    const reader = response.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      if (value && value.byteLength > 0) {
        chunks.push(value);
        loaded += value.byteLength;
        options.onProgress?.({ loaded, total, percent: percentOf(loaded, total) });
      }
    }
  } else {
    const buffer = new Uint8Array(await response.arrayBuffer());
    chunks.push(buffer);
    loaded += buffer.byteLength;
    options.onProgress?.({ loaded, total, percent: percentOf(loaded, total) });
  }

  const blob = new Blob(chunks);
  return { blob, fileName: parseFileName(response.headers.get('Content-Disposition')) };
}
