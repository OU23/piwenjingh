/**
 * 测试范围：分片上传的**暂停 / 继续**语义（src/api/chunkUpload.ts）
 * 测试类型：单元（桩替换 axios adapter，不发起真实请求）
 * 测试目的：锁死"暂停真的停、继续真能续、取消不卡死"三条行为
 * 防回归问题：
 *   1. 暂停只改界面状态、未通知上传任务（`pause()` 未被调用）→ 暂停后分片照传；
 *   2. `resumeWaiters` 退化为单槽 → 并发 worker 只被唤醒一个，其余永久阻塞，
 *      `drain()` 的 `Promise.all` 永不结算 → `run()` 卡住（合并与后续步骤都不执行）；
 *   3. 暂停中止在途分片后被误判为"失败"→ 消耗重试次数甚至抛错。
 * 关键不变量：
 *   - 暂停后**不再发出新的分片请求**，且在途分片被中止；
 *   - 恢复后被中止的分片**无损重传**，最终 1..totalChunks 全部成功送达；
 *   - 并发 worker 同时暂停后，恢复能**全部唤醒**（`run()` 必定结算）；
 *   - 暂停态下取消 `run()` 以 UploadCanceledError 结束，不悬挂。
 * 边界说明：不覆盖真实网络与浏览器 abort 实现差异；不覆盖后端合并行为。
 * 依赖与限制：jsdom 环境（File / FormData 可用）。桩适配器**自行模拟 abort**——
 *   真实环境里 `signal` 由 axios 的 xhr 适配器处理，自定义适配器不模拟就测不出"点即停"。
 */
import { afterEach, describe, expect, it } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { http } from '@/api/request';
import { ChunkUploadTask, MAX_CONCURRENT_CHUNKS, UploadCanceledError } from '@/api/chunkUpload';

/** 标识由调用方异步算出后传入；调度逻辑本身与该值无关，测试里给固定常量即可 */
const TEST_IDENTIFIER = 'tbf-0000000000000000-24';

/** 文件 24 字节 + 分片 4 字节 → 6 片 */
const FILE_SIZE = 24;
const CHUNK_SIZE = 4;
const TOTAL_CHUNKS = FILE_SIZE / CHUNK_SIZE;

function makeFile(size = FILE_SIZE): File {
  return new File([new Uint8Array(size)], 'demo.zip', { lastModified: 1_700_000_000_000 });
}

/** 让微任务与宏任务都推进若干轮，等待异步链路稳定 */
async function flush(rounds = 10): Promise<void> {
  for (let index = 0; index < rounds; index += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

interface PendingChunk {
  settle: () => void;
  abort: () => void;
}

/**
 * 桩服务端：check / merge / cancel 立即返回；**分片请求挂起**，由测试显式放行。
 * 分片请求响应 abort 信号（模拟 axios xhr 适配器的行为）。
 */
function startServer() {
  const requested: number[] = []; // 实际发出的分片请求序号（含被中止后重传的）
  const delivered: number[] = []; // 成功送达的分片序号
  const pending: PendingChunk[] = [];
  let autoResolve = false;

  const respond = (config: InternalAxiosRequestConfig, data: unknown): AxiosResponse => ({
    data: { code: 0, message: 'ok', data },
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  });

  http.defaults.adapter = ((config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const url = config.url ?? '';
    const method = (config.method ?? 'get').toLowerCase();

    if (url.endsWith('/api/upload/check')) {
      return Promise.resolve(respond(config, { uploadedChunks: [] }));
    }
    if (url.endsWith('/api/upload/merge')) {
      return Promise.resolve(respond(config, { fileKey: 'file-key-1', fileName: 'demo.zip', fileSize: FILE_SIZE }));
    }
    if (method === 'delete') {
      return Promise.resolve(respond(config, null));
    }

    // 其余视为分片上传
    const chunkNumber = Number((config.data as FormData).get('chunkNumber'));
    requested.push(chunkNumber);

    return new Promise<AxiosResponse>((resolve, reject) => {
      const signal = config.signal as AbortSignal | undefined;
      const entry: PendingChunk = { settle: () => {}, abort: () => {} };
      let done = false;

      /** 保证每个请求恰好结算一次（中止后不再被放行重复结算） */
      const finish = (action: () => void) => {
        if (done) {
          return;
        }
        done = true;
        const index = pending.indexOf(entry);
        if (index >= 0) {
          pending.splice(index, 1);
        }
        action();
      };

      entry.settle = () =>
        finish(() => {
          delivered.push(chunkNumber);
          resolve(respond(config, null));
        });
      entry.abort = () => finish(() => reject(new Error('aborted'))); // 模拟 axios 的 CanceledError

      if (signal?.aborted) {
        entry.abort();
        return;
      }
      signal?.addEventListener('abort', () => entry.abort(), { once: true });

      if (autoResolve) {
        entry.settle();
        return;
      }
      pending.push(entry);
    });
  }) as AxiosAdapter;

  return {
    requested,
    delivered,
    /** 放行全部分片请求，并让后续到达的分片请求也直接成功 */
    releaseAll: () => {
      autoResolve = true;
      for (const entry of pending.splice(0)) {
        entry.settle();
      }
    },
  };
}

afterEach(() => {
  http.defaults.adapter = undefined;
});

describe('分片上传的暂停与继续', () => {
  it('暂停：中止在途分片且不再发起新请求；恢复后无损续传并最终完成', async () => {
    const server = startServer();
    const task = new ChunkUploadTask({ file: makeFile(), identifier: TEST_IDENTIFIER, chunkSize: CHUNK_SIZE });

    const running = task.run();
    await flush();

    // 并发上限决定暂停前最多 MAX_CONCURRENT_CHUNKS 片在途
    expect(server.requested.length).toBe(MAX_CONCURRENT_CHUNKS);

    task.pause();
    await flush();

    // 暂停后即使再等待，也不应有新的分片请求发出
    const afterPause = server.requested.length;
    await flush(4);
    expect(server.requested.length).toBe(afterPause);
    expect(afterPause).toBe(MAX_CONCURRENT_CHUNKS);

    // 仍处于暂停：调度不应结算
    let settled = false;
    void running.then(
      () => {
        settled = true;
      },
      () => {
        settled = true;
      },
    );
    await flush();
    expect(settled).toBe(false);

    // 恢复：放行分片请求后应完整跑完
    server.releaseAll();
    task.resume();
    await expect(running).resolves.toEqual({ fileKey: 'file-key-1', fileName: 'demo.zip', fileSize: FILE_SIZE });

    // 被中止的分片重传，最终 1..totalChunks 全部成功送达（不丢片）
    expect([...server.delivered].sort((left, right) => left - right)).toEqual(
      Array.from({ length: TOTAL_CHUNKS }, (_, index) => index + 1),
    );
  });

  it('多个 worker 同时暂停后恢复能被全部唤醒（防等待者被覆盖导致 run() 永不结算）', async () => {
    const server = startServer();
    const task = new ChunkUploadTask({ file: makeFile(), identifier: TEST_IDENTIFIER, chunkSize: CHUNK_SIZE });

    const running = task.run();
    await flush();
    // 此时 MAX_CONCURRENT_CHUNKS 个 worker 都会停在暂停点
    task.pause();
    await flush();

    server.releaseAll();
    task.resume();

    // 单槽实现会让部分 worker 永久阻塞 → 此处永不结算（超时失败）
    await expect(running).resolves.toBeTruthy();
    // 恢复后确实继续发出了新请求（不是靠"恰好在暂停前传完"侥幸通过）
    expect(server.requested.length).toBeGreaterThan(MAX_CONCURRENT_CHUNKS);
  });

  it('暂停状态下取消：run() 不悬挂，以取消错误结束', async () => {
    startServer();
    const task = new ChunkUploadTask({ file: makeFile(), identifier: TEST_IDENTIFIER, chunkSize: CHUNK_SIZE });

    const running = task.run();
    await flush();
    task.pause();
    await flush();

    await task.cancel();

    await expect(running).rejects.toBeInstanceOf(UploadCanceledError);
  });

  it('暂停中止不会被当作失败：反复暂停恢复也不抛错（不消耗重试次数）', async () => {
    const server = startServer();
    const task = new ChunkUploadTask({ file: makeFile(), identifier: TEST_IDENTIFIER, chunkSize: CHUNK_SIZE });

    const running = task.run();
    await flush();

    // 连续暂停再恢复若干次；若中止被当成失败，早就耗尽重试次数并抛错
    for (let round = 0; round < 3; round += 1) {
      task.pause();
      await flush();
      task.resume();
      await flush();
    }

    server.releaseAll();
    await expect(running).resolves.toBeTruthy();
  });

  it('恢复后不重复计入已完成分片：成功送达的分片数等于总片数', async () => {
    const server = startServer();
    const task = new ChunkUploadTask({ file: makeFile(), identifier: TEST_IDENTIFIER, chunkSize: CHUNK_SIZE });

    const running = task.run();
    await flush();
    task.pause();
    await flush();
    server.releaseAll();
    task.resume();
    await running;

    expect(server.delivered).toHaveLength(TOTAL_CHUNKS);
  });
});
