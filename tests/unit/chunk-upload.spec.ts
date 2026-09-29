/**
 * 测试范围：分片上传调度（src/api/chunkUpload.ts）——字段名、序号规则、断点续传、取消清理
 * 测试类型：单元（桩替换 axios adapter，不发起真实请求）
 * 测试目的：锁死契约 §八 的字段与"只补传缺失分片"这两条最易翻车的口径
 * 防回归问题：字段名被改（filename / file）、序号从 0 开始、续传时从头重传、取消只清界面不清服务端
 * 关键不变量：
 *   - 分片请求体键名恰为 chunk / identifier / chunkNumber / totalChunks / fileName（无多余字段）
 *   - chunkNumber 从 1 开始，最大等于 totalChunks；totalChunks 向上取整
 *   - check 返回已传分片时，只发起缺失分片的请求（断言序号集合）
 *   - 取消 → 调用取消接口（DELETE）；离开页面 → 只停不清理
 * 边界说明：不覆盖真实网络与 GB 级传输（P5 实测）；不覆盖后端合并行为
 * 依赖与限制：jsdom 环境（File / FormData 可用）
 */
import { afterEach, describe, expect, it } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { http } from '@/api/request';
import {
  CHUNK_SIZE,
  ChunkUploadTask,
  SAMPLE_SEGMENT_SIZE,
  UploadCanceledError,
  computeTotalChunks,
  createFileIdentifier,
  pickPendingChunks,
  sliceChunk,
} from '@/api/chunkUpload';

interface RecordedCall {
  method: string;
  url: string;
  data: unknown;
}

/** 桩服务端：记录请求，按 URL 返回契约约定的响应 */
function startServer(options: { uploadedChunks?: number[]; fileKey?: string } = {}) {
  const calls: RecordedCall[] = [];
  http.defaults.adapter = (async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const method = (config.method ?? 'get').toLowerCase();
    const url = config.url ?? '';
    calls.push({ method, url, data: config.data });

    const respond = (data: unknown): AxiosResponse => ({
      data: { code: 0, message: 'ok', data },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    });

    if (url.endsWith('/api/upload/check')) {
      return respond({ uploadedChunks: options.uploadedChunks ?? [] });
    }
    if (url.endsWith('/api/upload/merge')) {
      return respond({ fileKey: options.fileKey ?? 'file-key-1', fileName: 'demo.zip', fileSize: 10 });
    }
    return respond(null);
  }) as AxiosAdapter;

  return {
    calls,
    chunkCalls: () =>
      calls
        .filter((call) => call.url.endsWith('/api/upload/chunk'))
        .map((call) => (call.data as FormData).get('chunkNumber'))
        .map((value) => Number(value))
        .sort((left, right) => left - right),
    bodyOf: (suffix: string) => {
      const data = calls.find((call) => call.url.endsWith(suffix))?.data;
      // axios 在交给 adapter 前已把 JSON body 序列化为字符串，这里还原
      return typeof data === 'string' ? JSON.parse(data) : data;
    },
  };
}

function makeFile(size: number, name = 'demo.zip', lastModified = 1_700_000_000_000): File {
  return new File([new Uint8Array(size)], name, { lastModified, type: 'application/zip' });
}

/** 标识由调用方异步算出后传入；调度逻辑本身与该值无关，测试里给固定常量即可 */
const TEST_IDENTIFIER = 'tbf-0000000000000000-10';

afterEach(() => {
  http.defaults.adapter = undefined;
});

describe('分片计算与切片', () => {
  it('总片数向上取整（契约 §8.3）', () => {
    expect(computeTotalChunks(10, 4)).toBe(3);
    expect(computeTotalChunks(8, 4)).toBe(2);
    expect(computeTotalChunks(4, 4)).toBe(1);
    expect(computeTotalChunks(0)).toBe(0);
  });

  it('切片按序号取，最后一片不足时取剩余（不读整文件）', () => {
    const file = makeFile(10);

    expect(sliceChunk(file, 1, 4).size).toBe(4);
    expect(sliceChunk(file, 3, 4).size).toBe(2);
  });

  it('缺失分片：从 1 开始，排除已传', () => {
    expect(pickPendingChunks([], 3)).toEqual([1, 2, 3]);
    expect(pickPendingChunks([1, 3], 4)).toEqual([2, 4]);
    expect(pickPendingChunks([1, 2, 3], 3)).toEqual([]);
  });

  it('文件标识：同一文件恒定；大小或内容变化则不同（采样哈希，补充说明 N-05）', async () => {
    const base = await createFileIdentifier(makeFile(10));

    // 同一文件重复上传 → 标识不变（断点续传的前提）
    expect(await createFileIdentifier(makeFile(10))).toBe(base);
    // 大小不同 → 不同
    expect(await createFileIdentifier(makeFile(11))).not.toBe(base);
    // 大小相同但内容不同 → 不同（这正是采样哈希相对纯元数据指纹的价值）
    const differentContent = new File([new Uint8Array(10).fill(7)], 'demo.zip', { lastModified: 1_700_000_000_000 });
    expect(await createFileIdentifier(differentContent)).not.toBe(base);
    // 格式：tbf-<哈希>-<大小>，后端按不透明字符串处理
    expect(base).toMatch(/^tbf-[0-9a-f]+-10$/);
  });

  it('大文件只采样不整读：超长文件也能算出标识', async () => {
    const large = makeFile(SAMPLE_SEGMENT_SIZE * 6);

    const identifier = await createFileIdentifier(large);

    expect(identifier).toMatch(new RegExp(`^tbf-[0-9a-f]+-${SAMPLE_SEGMENT_SIZE * 6}$`));
  });
});

describe('分片上传调度', () => {
  it('分片请求体键名与契约完全一致，且无多余字段', async () => {
    const server = startServer();

    const task = new ChunkUploadTask({ file: makeFile(10), identifier: TEST_IDENTIFIER, chunkSize: 4 });
    await task.run();

    const form = server.bodyOf('/api/upload/chunk') as FormData;
    expect([...form.keys()].sort()).toEqual(['chunk', 'chunkNumber', 'fileName', 'identifier', 'totalChunks'].sort());
    expect(form.get('identifier')).toBe(task.identifier);
    expect(form.get('totalChunks')).toBe('3');
    expect(form.get('fileName')).toBe('demo.zip');
    expect(form.get('chunk')).toBeInstanceOf(Blob);
  });

  it('检查请求体字段与分片序号规则正确', async () => {
    const server = startServer();

    const task = new ChunkUploadTask({ file: makeFile(10), identifier: TEST_IDENTIFIER, chunkSize: 4 });
    await task.run();

    expect(server.bodyOf('/api/upload/check')).toEqual({
      identifier: task.identifier,
      fileName: 'demo.zip',
      fileSize: 10,
      totalChunks: 3,
    });
    // 序号从 1 开始，最大等于 totalChunks
    expect(server.chunkCalls()).toEqual([1, 2, 3]);
    expect(task.totalChunks).toBe(3);
  });

  it('断点续传：只补传缺失分片，不重传已传部分', async () => {
    const server = startServer({ uploadedChunks: [1, 3] });
    const resumed: number[][] = [];

    const task = new ChunkUploadTask({
      file: makeFile(10),
      identifier: TEST_IDENTIFIER,
      chunkSize: 4,
      onChecked: (uploadedChunks) => resumed.push(uploadedChunks),
    });
    await task.run();

    expect(resumed).toEqual([[1, 3]]);
    expect(server.chunkCalls()).toEqual([2]); // 只传第 2 片
  });

  it('全部分片已存在时：不发分片请求，直接合并', async () => {
    const server = startServer({ uploadedChunks: [1, 2, 3] });

    const task = new ChunkUploadTask({ file: makeFile(10), identifier: TEST_IDENTIFIER, chunkSize: 4 });
    await task.run();

    expect(server.chunkCalls()).toEqual([]);
    expect(server.bodyOf('/api/upload/merge')).toEqual({
      identifier: task.identifier,
      fileName: 'demo.zip',
      fileSize: 10,
      totalChunks: 3,
    });
  });

  it('合并成功后返回文件标识', async () => {
    startServer();

    const task = new ChunkUploadTask({ file: makeFile(10), identifier: TEST_IDENTIFIER, chunkSize: 4 });
    await expect(task.run()).resolves.toEqual({ fileKey: 'file-key-1', fileName: 'demo.zip', fileSize: 10 });
  });

  it('取消：调用取消接口清理服务端分片，并以取消错误结束', async () => {
    const server = startServer();

    const task = new ChunkUploadTask({ file: makeFile(10), identifier: TEST_IDENTIFIER, chunkSize: 4 });
    await task.cancel();

    const cancelCall = server.calls.find((call) => call.method === 'delete');
    expect(cancelCall).toBeTruthy();
    expect(cancelCall?.url).toContain(encodeURIComponent(task.identifier));

    await expect(task.run()).rejects.toBeInstanceOf(UploadCanceledError);
  });

  it('默认分片大小为 5 MB', () => {
    expect(CHUNK_SIZE).toBe(5 * 1024 * 1024);
  });
});
