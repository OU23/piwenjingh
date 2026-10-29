/**
 * 测试范围：上传模式查询（src/api/uploadMode.ts）与上传上下文的模式/分片大小装配（src/api/uploadContext.ts）
 * 测试类型：单元（桩替换请求层）
 * 测试目的：锁死"进入上传页先问模式、据此分流"与"分片参数唯一来源是后端"（M-23 验收 A1 / A2 / A20）
 * 防回归问题：模式读取失败即瘫痪上传、分片大小退回前端写死值、mode 失败时 partSize 取不到兜底
 * 关键不变量：
 *   - `getUploadMode` 命中 `GET /api/upload/mode` 并原样返回后端参数
 *   - 上下文 `mode` 仅在后端明确返回 direct 时为 direct，其余（含失败）一律 server
 *   - `partSize` 优先取 mode 值；mode 失败时退回设置里的 chunkSize；都拿不到才为 0（**不写死**）
 * 边界说明：不覆盖页面如何按模式选任务（另见 upload-direct-view.spec.ts）
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: mocks.get,
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

import { getUploadMode, type UploadModeVO } from '@/api/uploadMode';
import { loadUploadContext } from '@/api/uploadContext';

const MODE: UploadModeVO = {
  mode: 'direct',
  partSize: 20971520,
  partSizeMin: 5242880,
  partSizeMax: 104857600,
  maxFileSize: 10737418240,
};

function stubGet(handlers: { mode?: () => Promise<unknown>; settings?: () => Promise<unknown> }) {
  mocks.get.mockImplementation((url: string) => {
    if (url === '/api/upload/mode') {
      return handlers.mode ? handlers.mode() : Promise.resolve(MODE);
    }
    if (url === '/api/settings') {
      return handlers.settings
        ? handlers.settings()
        : Promise.resolve({ storageRoot: '/data/toolbox/files', maxFileSize: 10737418240, chunkSize: 52428800 });
    }
    return Promise.resolve([]);
  });
}

beforeEach(() => {
  mocks.get.mockReset();
});

describe('上传模式查询', () => {
  it('命中 GET /api/upload/mode 并原样返回后端参数', async () => {
    stubGet({});

    const mode = await getUploadMode();

    expect(mocks.get).toHaveBeenCalledWith('/api/upload/mode');
    expect(mode).toEqual(MODE);
  });
});

describe('上传上下文的模式与分片大小', () => {
  it('mode=direct → 上下文为 direct，partSize 取 mode 的权威值', async () => {
    stubGet({});

    const context = await loadUploadContext();

    expect(context.mode).toBe('direct');
    expect(context.partSize).toBe(20971520);
  });

  it('mode=server → 上下文为 server（仍取 mode 的 partSize）', async () => {
    stubGet({ mode: () => Promise.resolve({ ...MODE, mode: 'server', partSize: 52428800 }) });

    const context = await loadUploadContext();

    expect(context.mode).toBe('server');
    expect(context.partSize).toBe(52428800);
  });

  it('模式接口失败 → 兜底 server，分片大小退回设置里的 chunkSize', async () => {
    stubGet({ mode: () => Promise.reject(new Error('模式服务不可用')) });

    const context = await loadUploadContext();

    expect(context.mode).toBe('server');
    expect(context.partSize).toBe(52428800);
  });

  it('模式与设置都失败 → server 且 partSize 为 0（不写死前端默认值）', async () => {
    stubGet({
      mode: () => Promise.reject(new Error('模式服务不可用')),
      settings: () => Promise.reject(new Error('设置读取失败')),
    });

    const context = await loadUploadContext();

    expect(context.mode).toBe('server');
    expect(context.partSize).toBe(0);
    expect(context.maxFileSizeError).toBe('设置读取失败');
  });
});
