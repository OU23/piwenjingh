/**
 * 测试范围：鉴权下载核心（src/api/download.ts）——请求形态、Range 续传、流式写入、失败分支
 * 测试类型：单元（注入假保存目标，不触盘）
 * 测试目的：锁死"带令牌请求 + 流式写盘 + 可续传 + 不直链"这四条（principles.md R4 / R5，契约 §9.5）
 * 防回归问题：用静态直链下载、把整个文件读进内存、续传时从头开始、中断后丢弃已写内容、失败时静默
 * 关键不变量：
 *   - 请求头携带 Authorization；续传时携带 Range: bytes=<已写>-
 *   - 206 → 追加写；200（服务端忽略 Range）→ 从头写并清空旧内容
 *   - 中断 → done = false 且已写内容落盘（不调用 abort）
 *   - 401 / 403 / 业务码分别抛出可识别错误
 * 边界说明：不覆盖真实网络与浏览器保存框；GB 级实测见 P5
 * 依赖与限制：jsdom 环境；fetch 与保存目标均为桩
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BizError, ForbiddenError, UnauthorizedError } from '@/api/request';
import { fileSystemProvider, type SaveTarget } from '@/api/saveTarget';
import {
  fetchAsBlob,
  parseTotalSize,
  percentOf,
  probeTotalSize,
  streamToTarget,
} from '@/api/download';

/**
 * 读取 Blob 文本。
 * 生产代码用 `new Blob(chunks)` 逐片拼装（浏览器里 Blob 自带 `.text()`）；
 * jsdom 的 Blob 可能没有 `.text()`，故回退到 `FileReader`——**只换读取方式，内容断言不变**。
 */
async function blobToText(blob: Blob): Promise<string> {
  const maybe = blob as Blob & { text?: () => Promise<string> };
  if (typeof maybe.text === 'function') {
    return maybe.text();
  }
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error('读取 Blob 失败'));
    reader.readAsText(blob);
  });
}

interface FakeTarget {
  target: SaveTarget;
  chunks: Uint8Array[];
  seeks: number[];
  truncates: number[];
  closes: number;
  aborts: number;
}

function createFakeTarget(): FakeTarget {
  const fake: FakeTarget = {
    chunks: [],
    seeks: [],
    truncates: [],
    closes: 0,
    aborts: 0,
    target: null as unknown as SaveTarget,
  };

  fake.target = {
    open: async () => ({
      write: async (chunk: Uint8Array) => {
        fake.chunks.push(chunk);
      },
      seek: async (position: number) => {
        fake.seeks.push(position);
      },
      truncate: async (size: number) => {
        fake.truncates.push(size);
      },
      close: async () => {
        fake.closes += 1;
      },
      abort: async () => {
        fake.aborts += 1;
      },
    }),
  };

  return fake;
}

function bytes(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

function writtenText(fake: FakeTarget): string {
  const size = fake.chunks.reduce((sum, item) => sum + item.byteLength, 0);
  const buffer = new Uint8Array(size);
  let offset = 0;
  fake.chunks.forEach((item) => {
    buffer.set(item, offset);
    offset += item.byteLength;
  });
  return new TextDecoder().decode(buffer);
}

function streamOf(...texts: string[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      texts.forEach((text) => controller.enqueue(bytes(text)));
      controller.close();
    },
  });
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('鉴权下载核心', () => {
  it('带令牌请求并边收边写，结束后落盘', async () => {
    const fetchMock = vi.fn(
      async () => new Response(streamOf('ab', 'cd'), { status: 200, headers: { 'Content-Length': '4' } }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const fake = createFakeTarget();
    const progress: number[] = [];
    const result = await streamToTarget({
      url: '/api/tools/1001/download',
      token: 'token-value',
      target: fake.target,
      signal: new AbortController().signal,
      onProgress: (item) => progress.push(item.loaded),
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token-value');
    // 首次下载不带 Range
    expect((init.headers as Record<string, string>).Range).toBeUndefined();

    expect(writtenText(fake)).toBe('abcd');
    expect(result).toEqual({ done: true, written: 4, total: 4 });
    expect(fake.closes).toBe(1);
    expect(fake.aborts).toBe(0);
    expect(progress).toEqual([0, 2, 4]);
  });

  it('续传：携带 Range 且按 206 追加写入', async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(streamOf('cd'), {
          status: 206,
          headers: { 'Content-Range': 'bytes 2-3/4', 'Content-Length': '2' },
        }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const fake = createFakeTarget();
    const result = await streamToTarget({
      url: '/api/tools/1001/download',
      token: 'token-value',
      target: fake.target,
      offset: 2,
      signal: new AbortController().signal,
    });

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Range).toBe('bytes=2-');

    expect(fake.seeks).toEqual([2]);
    expect(writtenText(fake)).toBe('cd');
    expect(result).toEqual({ done: true, written: 4, total: 4 });
  });

  it('服务端不支持 Range（返回 200）：从头写并清空旧内容', async () => {
    const fetchMock = vi.fn(
      async () => new Response(streamOf('abcd'), { status: 200, headers: { 'Content-Length': '4' } }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const fake = createFakeTarget();
    const result = await streamToTarget({
      url: '/api/tools/1001/download',
      token: 'token-value',
      target: fake.target,
      offset: 2,
      signal: new AbortController().signal,
    });

    expect(fake.seeks).toEqual([0]);
    expect(fake.truncates).toEqual([4]);
    expect(result).toEqual({ done: true, written: 4, total: 4 });
  });

  it('中断：返回 done = false，已写内容落盘且不丢弃', async () => {
    const controller = new AbortController();
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      const signal = init.signal as AbortSignal;
      const stream = new ReadableStream<Uint8Array>({
        start(streamController) {
          streamController.enqueue(bytes('ab'));
          signal.addEventListener('abort', () => {
            streamController.error(new DOMException('Aborted', 'AbortError'));
          });
        },
      });
      return new Response(stream, { status: 200, headers: { 'Content-Length': '4' } });
    });
    vi.stubGlobal('fetch', fetchMock);

    const fake = createFakeTarget();
    const result = await streamToTarget({
      url: '/api/tools/1001/download',
      token: 'token-value',
      target: fake.target,
      signal: controller.signal,
      onProgress: (item) => {
        if (item.loaded >= 2) {
          controller.abort();
        }
      },
    });

    expect(result.done).toBe(false);
    expect(result.written).toBe(2);
    expect(writtenText(fake)).toBe('ab');
    expect(fake.closes).toBe(1);
    // abort 会丢弃已写内容，因此中断时必须走 close
    expect(fake.aborts).toBe(0);
  });

  it('失败分支：401 / 403 / 业务码分别可识别', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse(401, { code: 1001, message: '登录状态已失效，请重新登录', data: null })),
    );
    await expect(
      streamToTarget({
        url: '/api/tools/1/download',
        token: 'expired',
        target: createFakeTarget().target,
        signal: new AbortController().signal,
      }),
    ).rejects.toBeInstanceOf(UnauthorizedError);

    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(403, { code: 1002, message: '无操作权限', data: null })));
    await expect(
      streamToTarget({
        url: '/api/tools/1/download',
        token: 'token-value',
        target: createFakeTarget().target,
        signal: new AbortController().signal,
      }),
    ).rejects.toBeInstanceOf(ForbiddenError);

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse(200, { code: 2004, message: '该工具不存在或已被删除', data: null })),
    );
    await expect(
      streamToTarget({
        url: '/api/tools/1/download',
        token: 'token-value',
        target: createFakeTarget().target,
        signal: new AbortController().signal,
      }),
    ).rejects.toMatchObject({ code: 2004, message: '该工具不存在或已被删除' });
  });

  it('总量解析与进度计算', () => {
    expect(parseTotalSize('bytes 0-1048575/3145728', null)).toBe(3145728);
    expect(parseTotalSize(null, '1024', 0)).toBe(1024);
    expect(parseTotalSize(null, '1024', 2048)).toBe(3072);
    expect(parseTotalSize(null, null)).toBeNull();
    expect(parseTotalSize('bytes 0-0/*', null)).toBeNull();

    expect(percentOf(0, 100)).toBe(0);
    expect(percentOf(50, 100)).toBe(50);
    expect(percentOf(200, 100)).toBe(100);
    expect(percentOf(10, null)).toBeNull();
  });

  it('探测总量：用 HEAD 且不带 Range（避免污染下载计数，补充说明 N-02）', async () => {
    const fetchMock = vi.fn(
      async () => new Response(null, { status: 200, headers: { 'Content-Length': '8388608', 'Accept-Ranges': 'bytes' } }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const total = await probeTotalSize({
      url: '/api/tools/1/download',
      token: 'token-value',
      signal: new AbortController().signal,
    });

    expect(total).toBe(8388608);
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.method).toBe('HEAD');
    expect((init.headers as Record<string, string>).Range).toBeUndefined();
  });

  it('降级路径：整包读取并解析中文文件名', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(streamOf('demo'), {
            status: 200,
            headers: {
              'Content-Disposition': "attachment; filename=\"tool.zip\"; filename*=UTF-8''%E5%B7%A5%E5%85%B7.zip",
            },
          }),
      ),
    );

    const result = await fetchAsBlob({
      url: '/api/tools/1/download',
      token: 'token-value',
      signal: new AbortController().signal,
    });

    expect(result.fileName).toBe('工具.zip');
    expect(await blobToText(result.blob)).toBe('demo');
  });

  it('无流式写盘能力时标记为不支持（降级路径的前置判断）', () => {
    expect(fileSystemProvider.supported).toBe(false);

    vi.stubGlobal('showSaveFilePicker', vi.fn());
    expect(fileSystemProvider.supported).toBe(true);
  });

  it('用户在保存框中取消：按未开始处理（不抛错）', async () => {
    vi.stubGlobal(
      'showSaveFilePicker',
      vi.fn(async () => {
        throw new DOMException('The user aborted a request.', 'AbortError');
      }),
    );

    await expect(fileSystemProvider.pick('tool.zip')).resolves.toBeNull();
  });

  it('未识别的失败响应：抛出 BizError 并保留可读文案', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 500 })));

    await expect(
      streamToTarget({
        url: '/api/tools/1/download',
        token: 'token-value',
        target: createFakeTarget().target,
        signal: new AbortController().signal,
      }),
    ).rejects.toBeInstanceOf(BizError);
  });
});
