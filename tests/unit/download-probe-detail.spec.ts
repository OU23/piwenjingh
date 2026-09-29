/**
 * 测试范围：下载探测失败的原因读取（src/api/download.ts —— probeTotalSize / readFailureDetail）
 * 测试类型：单元（stub 掉 fetch，不触网）
 * 测试目的：HEAD 探测失败时补一次 GET，把后端真正的 code / message 呈现出来（而非"下载失败（HTTP 200）"）
 * 防回归问题：HEAD 无响应体 → 前端读不到后端原因，只能给笼统文案
 * 关键不变量：
 *   - HEAD 失败 → 追加一次 GET，且带 `Range: bytes=1-1`（起点 > 0 按契约 N-02 不累计下载次数）
 *   - GET 回 JSON → 抛出的错误带后端 message（业务码 / 403 各自成类）
 *   - 探测成功 → 不发起额外 GET，只发一次 HEAD
 * 边界说明：不覆盖真实网络与真机下载
 * 依赖与限制：jsdom；fetch 为桩
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ForbiddenError } from '@/api/request';
import { probeTotalSize } from '@/api/download';

const SIGNAL = new AbortController().signal;
const URL_UNDER_TEST = '/api/tools/5/download';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('下载探测失败的原因读取', () => {
  it('HEAD 以 JSON 回绝（HTTP 200 + 业务码）→ 用 GET 取到后端 message', async () => {
    const calls: { method: string; range?: string }[] = [];

    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: RequestInit) => {
        const method = init?.method ?? 'GET';
        const headers = init?.headers as Record<string, string> | undefined;
        calls.push({ method, range: headers?.['Range'] });

        return method === 'HEAD' ? jsonResponse({}) : jsonResponse({ code: 2004, message: '该工具不存在或已被删除' });
      }),
    );

    await expect(probeTotalSize({ url: URL_UNDER_TEST, token: 'tk', signal: SIGNAL })).rejects.toThrowError(
      '该工具不存在或已被删除',
    );

    // 补发的是 GET，且带 Range（避免把探测请求计成一次下载）
    expect(calls.map((item) => item.method)).toEqual(['HEAD', 'GET']);
    expect(calls[1]?.range).toBe('bytes=1-1');
  });

  it('HEAD 403 → GET 取到后端原因，抛 ForbiddenError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ code: 1002, message: '无操作权限' }, 403)),
    );

    await expect(probeTotalSize({ url: URL_UNDER_TEST, token: 'tk', signal: SIGNAL })).rejects.toBeInstanceOf(
      ForbiddenError,
    );
  });

  it('探测成功（标准文件响应）→ 不发起额外 GET，直接返回文件大小', async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(null, {
          status: 200,
          headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': '2048' },
        }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(probeTotalSize({ url: URL_UNDER_TEST, token: 'tk', signal: SIGNAL })).resolves.toBe(2048);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
