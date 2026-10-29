/**
 * 测试范围：OSS 直传（src/api/directUpload.ts）——取凭证 → 直传 → 完成登记的调用序列、
 *           对象键复用（凭证过期自动续传）、断点续传 checkpoint、取消
 * 测试类型：单元（桩替换 `ali-oss` 与请求层，不发起真实请求）
 * 测试目的：锁死契约 v1.20 §8.6 / §8.7 的三条口径（M-23 验收 A20 / A21）
 * 防回归问题：前端拿长期 AK/SK、partSize 用本地值、凭证过期后从头重传、取消不中止 OSS 会话、
 *           **复用已失效的 uploadId 导致 404 后自锁（无法重试成功）**
 * 关键不变量：
 *   - 凭证来自 `/api/upload/direct/credentials`，`partSize` 用**后端回显值**
 *   - 403 / InvalidAccessKeyId / SecurityTokenExpired → 重取凭证且**回传同一 objectKey**，带 checkpoint 续传
 *   - 成功 → 调 `complete` 并返回 `fileKey`
 * 边界说明：不覆盖真实 OSS 网络行为（B 组 ⛓ 项待部署侧联调）
 * 依赖与限制：jsdom（localStorage 可用）
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  instantiate: vi.fn(),
  multipartUpload: vi.fn(),
  cancel: vi.fn(),
  abortMultipartUpload: vi.fn(),
  post: vi.fn(),
}));

/** 桩 `ali-oss`：只保留直传用到的入口 */
vi.mock('ali-oss', () => {
  class MockOss {
    constructor(options: unknown) {
      mocks.instantiate(options);
    }

    multipartUpload = mocks.multipartUpload;
    cancel = mocks.cancel;
    abortMultipartUpload = mocks.abortMultipartUpload;
  }

  return { default: MockOss, __esModule: true };
});

vi.mock('@/api/request', () => ({
  post: mocks.post,
  get: vi.fn(),
}));

import {
  DirectUploadTask,
  clearCheckpoint,
  isCredentialExpired,
  isUploadGone,
  readCheckpoint,
  type UploadCredentialVO,
} from '@/api/directUpload';
import { UploadCanceledError } from '@/api/chunkUpload';

const PART_SIZE = 5 * 1024 * 1024;
const OBJECT_KEY = 'tools/7/0123456789abcdef0123456789abcdef.zip';
const IDENTIFIER = 'tbf-0000000000000000-10';
const CHECKPOINT_KEY = 'toolbox-direct-upload:' + IDENTIFIER;

interface Recorded {
  url: string;
  body: Record<string, unknown>;
}

let credentialCalls: Recorded[];
let completeCalls: Recorded[];
/** 进行中（未结算）的分片上传的 reject 回调，供模拟 `client.cancel()` 中止 */
let pendingRejects: ((error: unknown) => void)[];

function credential(overrides: Partial<UploadCredentialVO> = {}): UploadCredentialVO {
  return {
    objectKey: OBJECT_KEY,
    region: 'oss-cn-hangzhou',
    endpoint: 'oss-cn-hangzhou.aliyuncs.com',
    bucket: 'toolbox-files',
    credentials: { accessKeyId: 'STS-ak', accessKeySecret: 'STS-sk', securityToken: 'STS-token' },
    expiration: '2026-10-13T09:30:00Z',
    partSize: PART_SIZE,
    maxParts: 10000,
    ...overrides,
  };
}

function sdkResult() {
  return { bucket: 'toolbox-files', name: OBJECT_KEY, etag: 'etag-1', data: {}, res: {} };
}

/** 桩 checkpoint（与 ali-oss 的 Checkpoint 同形） */
function sdkCheckpoint(uploadId = 'upload-1') {
  return {
    file: null,
    name: OBJECT_KEY,
    fileSize: 10,
    partSize: PART_SIZE,
    uploadId,
    doneParts: [{ number: 1, etag: 'e1' }],
  };
}

function makeFile(size = 10, name = 'demo.zip') {
  return new File([new Uint8Array(size)], name, { type: 'application/zip' });
}

function newTask(size = 10) {
  return new DirectUploadTask({ file: makeFile(size), identifier: IDENTIFIER, partSize: PART_SIZE });
}

/** 让"进行中"的上传永挂起，并在 client.cancel() 时以取消错误中止（还原 SDK 行为） */
function stubOngoingUpload(): void {
  mocks.multipartUpload.mockImplementation(
    () =>
      new Promise((_resolve, reject) => {
        pendingRejects.push(reject);
      }),
  );
}

function seedCheckpoint(uploadId: string): void {
  localStorage.setItem(
    CHECKPOINT_KEY,
    JSON.stringify({
      objectKey: OBJECT_KEY,
      uploadId,
      partSize: PART_SIZE,
      fileSize: 10,
      doneParts: [],
    }),
  );
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  localStorage.clear();
  credentialCalls = [];
  completeCalls = [];
  pendingRejects = [];
  mocks.instantiate.mockClear();
  mocks.multipartUpload.mockReset();
  mocks.abortMultipartUpload.mockReset();
  mocks.post.mockReset();

  mocks.cancel.mockImplementation(() => {
    const rejects = pendingRejects;
    pendingRejects = [];
    for (const reject of rejects) {
      reject(Object.assign(new Error('cancel'), { name: 'cancel' }));
    }
  });

  mocks.post.mockImplementation((url: string, body: Record<string, unknown>) => {
    if (url === '/api/upload/direct/credentials') {
      credentialCalls.push({ url, body });
      return Promise.resolve(credential());
    }
    if (url === '/api/upload/direct/complete') {
      completeCalls.push({ url, body });
      return Promise.resolve({ fileKey: body.objectKey, fileName: body.fileName, fileSize: body.fileSize });
    }
    return Promise.reject(new Error('未预期的请求：' + url));
  });
});

afterEach(() => {
  localStorage.clear();
});

describe('直传调用序列（A20）', () => {
  it('取凭证 → 用临时凭证直传 → 完成登记，且用后端回显的 partSize', async () => {
    mocks.multipartUpload.mockImplementation(async (_name, _file, options) => {
      await options.progress(1, sdkCheckpoint());
      return sdkResult();
    });

    const result = await newTask().run();

    // ① 只请求了凭证接口（未走任何"经服务器中转"的分片接口）
    expect(credentialCalls).toHaveLength(1);
    expect(credentialCalls[0].body).toEqual({ fileName: 'demo.zip', fileSize: 10 });

    // ② 客户端用**临时凭证**构造（绝不使用长期 AK/SK）
    expect(mocks.instantiate).toHaveBeenCalledWith(
      expect.objectContaining({
        bucket: 'toolbox-files',
        accessKeyId: 'STS-ak',
        accessKeySecret: 'STS-sk',
        stsToken: 'STS-token',
      }),
    );

    // ③ 分片大小用后端回显值；并发与既有口径一致
    expect(mocks.multipartUpload).toHaveBeenCalledTimes(1);
    const options = mocks.multipartUpload.mock.calls[0][2];
    expect(options.partSize).toBe(PART_SIZE);
    expect(options.parallel).toBe(3);
    expect(mocks.multipartUpload.mock.calls[0][0]).toBe(OBJECT_KEY);

    // ④ 完成登记后返回 fileKey（与 merge 同形）
    expect(completeCalls).toHaveLength(1);
    expect(completeCalls[0].body).toEqual({ objectKey: OBJECT_KEY, fileName: 'demo.zip', fileSize: 10 });
    expect(result).toEqual({ fileKey: OBJECT_KEY, fileName: 'demo.zip', fileSize: 10 });
  });

  it('后端回显的 partSize 生效（自动上调场景）', async () => {
    const upgraded = 25 * 1024 * 1024;
    mocks.post.mockImplementation((url: string, body: Record<string, unknown>) => {
      if (url === '/api/upload/direct/credentials') {
        credentialCalls.push({ url, body });
        return Promise.resolve(credential({ partSize: upgraded }));
      }
      return Promise.resolve({ fileKey: body.objectKey, fileName: body.fileName, fileSize: body.fileSize });
    });
    mocks.multipartUpload.mockResolvedValue(sdkResult());

    await newTask().run();

    expect(mocks.multipartUpload.mock.calls[0][2].partSize).toBe(upgraded);
  });
});

describe('凭证过期自动续传（A21）', () => {
  it('403 → 重取凭证（复用同一 objectKey）→ 带 checkpoint 继续', async () => {
    // 第一次：已初始化分片上传并上报过 checkpoint，随后因凭证失效被拒
    mocks.multipartUpload
      .mockImplementationOnce(async (_name, _file, options) => {
        await options.progress(0.1, sdkCheckpoint());
        throw Object.assign(new Error('Forbidden'), { status: 403 });
      })
      .mockImplementationOnce(async (_name, _file, options) => {
        await options.progress(1, sdkCheckpoint());
        return sdkResult();
      });

    const result = await newTask().run();

    // 凭证被重取 2 次：首次不含 objectKey，续传那次**回传同一 objectKey**
    expect(credentialCalls).toHaveLength(2);
    expect(credentialCalls[0].body.objectKey).toBeUndefined();
    expect(credentialCalls[1].body.objectKey).toBe(OBJECT_KEY);

    // 重建客户端续传：第二次带 checkpoint，不从头重传
    expect(mocks.multipartUpload).toHaveBeenCalledTimes(2);
    expect(mocks.multipartUpload.mock.calls[0][2].checkpoint).toBeUndefined();
    expect(mocks.multipartUpload.mock.calls[1][2].checkpoint).toMatchObject({ uploadId: 'upload-1' });

    expect(result.fileKey).toBe(OBJECT_KEY);
  });

  it('SecurityTokenExpired 同样触发重取；其他错误不重试', async () => {
    mocks.multipartUpload.mockRejectedValueOnce(Object.assign(new Error('expired'), { code: 'SecurityTokenExpired' }));
    mocks.multipartUpload.mockResolvedValueOnce(sdkResult());

    await newTask().run();
    expect(credentialCalls).toHaveLength(2);

    // 非凭证类错误：不重试，直接抛出
    mocks.multipartUpload.mockReset();
    mocks.multipartUpload.mockRejectedValue(Object.assign(new Error('boom'), { code: 'SomeOtherError' }));
    credentialCalls = [];

    await expect(newTask().run()).rejects.toThrow('boom');
    expect(credentialCalls).toHaveLength(1);
  });

  it('凭证失效判定口径', () => {
    expect(isCredentialExpired({ status: 403 })).toBe(true);
    expect(isCredentialExpired({ code: 'InvalidAccessKeyId' })).toBe(true);
    expect(isCredentialExpired({ code: 'SecurityTokenExpired' })).toBe(true);
    expect(isCredentialExpired({ code: 'NoSuchKey' })).toBe(false);
    expect(isCredentialExpired(null)).toBe(false);
  });
});

describe('断点续传与取消', () => {
  it('已存 checkpoint → 续传时回传 objectKey 并带上 uploadId', async () => {
    seedCheckpoint('upload-9');
    mocks.multipartUpload.mockResolvedValue(sdkResult());

    await newTask().run();

    expect(credentialCalls[0].body.objectKey).toBe(OBJECT_KEY);
    expect(mocks.multipartUpload.mock.calls[0][2].checkpoint).toMatchObject({ uploadId: 'upload-9' });
  });

  it('成功后清理 checkpoint', async () => {
    mocks.multipartUpload.mockImplementation(async (_n, _f, options) => {
      await options.progress(0.5, sdkCheckpoint());
      return sdkResult();
    });

    await newTask().run();

    expect(readCheckpoint(IDENTIFIER)).toBeNull();
  });

  it('取消 → 中止 OSS 会话（abortMultipartUpload 带对象键与 uploadId）', async () => {
    seedCheckpoint('upload-7');
    stubOngoingUpload();

    const task = newTask();
    const running = task.run();
    await tick(); // 让取凭证 / 构造客户端 / 发起上传完成

    await task.cancel();

    expect(mocks.abortMultipartUpload).toHaveBeenCalledWith(OBJECT_KEY, 'upload-7');
    await expect(running).rejects.toBeInstanceOf(UploadCanceledError);
    // 本地续传记录同时作废
    expect(localStorage.getItem(CHECKPOINT_KEY)).toBeNull();
  });

  it('stop 只停止调度，不清除 checkpoint（离开页面后可续传）', async () => {
    seedCheckpoint('upload-5');
    stubOngoingUpload();

    const task = newTask();
    const running = task.run();
    await tick();
    task.stop();

    await expect(running).rejects.toBeInstanceOf(UploadCanceledError);
    expect(readCheckpoint(IDENTIFIER)).not.toBeNull();
    clearCheckpoint(IDENTIFIER);
  });
});

/**
 * 回归：断点记录"中毒"后的自愈。
 *
 * 背景（线上真实故障）：若"OSS 侧分片已合并成功、但后端 `complete` 登记失败"，
 * `clearCheckpoint` 不会执行 → localStorage 残留**已用完的 uploadId** →
 * 之后每次重试都复用它去 complete → OSS 返回 404（`upload does not exist`）→ **永久自锁**。
 */
describe('断点记录失效自愈', () => {
  it('复用已失效 uploadId 报 404 → 丢弃断点记录并重新发起一次', async () => {
    seedCheckpoint('upload-dead');
    mocks.multipartUpload
      .mockRejectedValueOnce(
        Object.assign(
          new Error(
            'The specified upload does not exist. The upload ID may be invalid, '
              + 'or the upload may have been aborted or completed.',
          ),
          { status: 404, code: 'NoSuchUpload' },
        ),
      )
      .mockResolvedValueOnce(sdkResult());

    const result = await newTask().run();

    // ① 首次确实复用了旧 uploadId（即故障触发条件）
    expect(mocks.multipartUpload).toHaveBeenCalledTimes(2);
    expect(mocks.multipartUpload.mock.calls[0][2].checkpoint).toMatchObject({ uploadId: 'upload-dead' });

    // ② 重发时**不再带断点记录**（重新 initiate）；objectKey 仍复用（同键重传，不产生孤儿对象）
    expect(mocks.multipartUpload.mock.calls[1][2].checkpoint).toBeUndefined();
    expect(credentialCalls[1].body.objectKey).toBe(OBJECT_KEY);

    // ③ 最终登记成功、返回 fileKey，且本地断点记录已清
    expect(completeCalls).toHaveLength(1);
    expect(result.fileKey).toBe(OBJECT_KEY);
    expect(readCheckpoint(IDENTIFIER)).toBeNull();
  });

  it('"上传已失效"只重发一次；仍失败则抛出（不无限重试）', async () => {
    seedCheckpoint('upload-dead');
    mocks.multipartUpload.mockRejectedValue(
      Object.assign(new Error('The specified upload does not exist.'), { status: 404, code: 'NoSuchUpload' }),
    );

    await expect(newTask().run()).rejects.toThrow('upload does not exist');
    // 1 次原发 + 1 次重发 = 2；不会继续重试
    expect(mocks.multipartUpload).toHaveBeenCalledTimes(2);
  });

  it('断点记录的分片大小与当前不一致 → 作废（不续传、也不回传 objectKey）', async () => {
    localStorage.setItem(
      CHECKPOINT_KEY,
      JSON.stringify({
        objectKey: OBJECT_KEY,
        uploadId: 'upload-stale',
        partSize: 1024, // ← 与当前 PART_SIZE 不同，片号归属会错乱
        fileSize: 10,
        doneParts: [],
      }),
    );
    mocks.multipartUpload.mockResolvedValue(sdkResult());

    await newTask().run();

    expect(mocks.multipartUpload.mock.calls[0][2].checkpoint).toBeUndefined();
    expect(credentialCalls[0].body.objectKey).toBeUndefined();
  });

  it('"上传已失效"判定口径', () => {
    expect(isUploadGone({ status: 404 })).toBe(true);
    expect(isUploadGone({ code: 'NoSuchUpload' })).toBe(true);
    expect(isUploadGone({ message: 'The specified upload does not exist. ...' })).toBe(true);
    expect(isUploadGone({ status: 403 })).toBe(false);
    expect(isUploadGone({ code: 'NoSuchKey' })).toBe(false);
    expect(isUploadGone(null)).toBe(false);
  });
});
