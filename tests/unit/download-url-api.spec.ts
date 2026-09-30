/**
 * 测试范围：下载地址签发与迁移接口的封装（src/api/tool.ts）
 * 测试类型：单元（桩掉请求层，不触网）
 * 测试目的：锁死"取地址 → 导航下载"的**地址归一化**与契约 v1.19 的接口形状
 * 防回归问题：把 OSS 绝对直链错误地再拼一次基址、把同源相对地址拼成前端自身地址、
 *   迁移接口形状漂移（任务模型字段/路径）
 * 关键不变量：
 *   - OSS 签名直链（绝对地址）**原样**返回
 *   - 后端代理地址（相对路径）：同源时原样；配了绝对 API 基址时补前缀且**不出现双斜杠**
 *   - `versionId` 省略时不带该查询参数
 *   - 迁移：`POST /api/tools/migrate-to-oss`（可带 limit）、`GET .../{taskId}`（taskId 需转义）
 * 边界说明：不覆盖真实网络与真实 OSS
 * 依赖与限制：@/api/request 为桩
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
  http: { defaults: { baseURL: '' as string } },
}));

vi.mock('@/api/request', () => ({
  get: mocks.get,
  post: mocks.post,
  put: mocks.put,
  del: mocks.del,
  http: mocks.http,
}));

import { getDownloadUrl, getMigrationStatus, startMigration } from '@/api/tool';

const SIGNED = 'https://toolbox-files.oss-cn-hangzhou.aliyuncs.com/tools/k.zip?Expires=1&Signature=x';

beforeEach(() => {
  mocks.get.mockReset();
  mocks.post.mockReset();
  mocks.http.defaults.baseURL = '';
});

describe('下载地址签发（getDownloadUrl）', () => {
  it('OSS 签名直链是绝对地址 → 原样返回，绝不重复拼基址', async () => {
    mocks.http.defaults.baseURL = 'https://api.example.com';
    mocks.get.mockResolvedValue({ url: SIGNED, expiresIn: 3600, direct: true });

    const vo = await getDownloadUrl('1001', '3005');

    expect(mocks.get).toHaveBeenCalledWith('/api/tools/1001/download-url', { versionId: '3005' });
    expect(vo.url).toBe(SIGNED);
    expect(vo.direct).toBe(true);
    expect(vo.expiresIn).toBe(3600);
  });

  it('不传 versionId → 请求不带该查询参数（后端取最新版）', async () => {
    mocks.get.mockResolvedValue({ url: '/api/tools/1/download?token=t', expiresIn: 60, direct: false });

    await getDownloadUrl('1001');

    expect(mocks.get).toHaveBeenCalledWith('/api/tools/1001/download-url', undefined);
  });

  it('同源部署（基址留空）：后端给的相对地址原样使用', async () => {
    mocks.get.mockResolvedValue({ url: '/api/tools/1/download?token=t', expiresIn: 60, direct: false });

    const vo = await getDownloadUrl('1');

    expect(vo.url).toBe('/api/tools/1/download?token=t');
    expect(vo.direct).toBe(false);
  });

  it('跨源部署（配了绝对基址）：相对地址补上前缀，且不出现双斜杠', async () => {
    mocks.get.mockResolvedValue({ url: '/api/tools/1/download?token=t', expiresIn: 60, direct: false });

    mocks.http.defaults.baseURL = 'https://api.example.com/';
    expect((await getDownloadUrl('1')).url).toBe('https://api.example.com/api/tools/1/download?token=t');

    mocks.http.defaults.baseURL = 'https://api.example.com';
    expect((await getDownloadUrl('1')).url).toBe('https://api.example.com/api/tools/1/download?token=t');
  });
});

describe('存量迁移接口（契约 §9.9 任务模型）', () => {
  it('发起迁移：不带 limit 时提交空对象', async () => {
    mocks.post.mockResolvedValue({ taskId: 'mig-1', status: 'COMPLETED' });

    await startMigration();

    expect(mocks.post).toHaveBeenCalledWith('/api/tools/migrate-to-oss', {});
  });

  it('发起迁移：带 limit 时按后端约定提交数字', async () => {
    mocks.post.mockResolvedValue({ taskId: 'mig-1', status: 'COMPLETED' });

    await startMigration(500);

    expect(mocks.post).toHaveBeenCalledWith('/api/tools/migrate-to-oss', { limit: 500 });
  });

  it('查询进度：taskId 需转义后拼进路径（可回查同一结果）', async () => {
    mocks.get.mockResolvedValue({ taskId: 'mig/1 2', status: 'RUNNING' });

    await getMigrationStatus('mig/1 2');

    expect(mocks.get).toHaveBeenCalledWith('/api/tools/migrate-to-oss/mig%2F1%202');
  });
});
