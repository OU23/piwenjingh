/**
 * 测试范围：导入导出 API 层（src/api/dept.ts / src/api/user.ts 的新增方法）
 * 测试类型：单元（纯模块，jsdom 无关）
 * 测试目的：锁死 M-19 / P8 的**请求形态**（acceptance A1 / A2 / A11）
 * 防回归问题：导入字段名写错（后端收不到文件）、手工设了 Content-Type 导致 boundary 丢失、
 *   模板 / 导出改用裸 URL（变成无鉴权静态直链）、导出漏传列表筛选条件
 * 关键不变量：
 *   - 模板 / 导出走 `request.download()`（**鉴权下载通道**，返回 Blob + 文件名），**不是**返回 URL 字符串
 *   - 导入用 `FormData`，字段名**固定为 `file`**，且**不传 config**（即不手工设置 Content-Type）
 *   - 导出的查询参数与列表页一致（用户域 `keyword` / `deptId`）
 * 边界说明：不覆盖真实 HTTP（拦截器加 `Authorization` 已在 P1 验证）
 * 依赖与限制：`@/api/request` 为桩
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { downloadDeptTemplate, exportDepts, importDepts } from '@/api/dept';
import { downloadUserTemplate, exportUsers, importUsers } from '@/api/user';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  download: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: mocks.get,
  post: mocks.post,
  put: mocks.put,
  download: mocks.download,
}));

const PAYLOAD = { blob: new Blob(['x']), fileName: '模板.xlsx' };

function makeFile(name = '导入.xlsx'): File {
  return new File(['x'], name);
}

beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset());
  mocks.download.mockResolvedValue(PAYLOAD);
  mocks.post.mockResolvedValue({ total: 0, successCount: 0, skipCount: 0, failCount: 0 });
});

describe('模板下载（A1）', () => {
  it('部门模板：调用 import-template 并走鉴权下载通道（返回 Blob 而非直链）', async () => {
    const result = await downloadDeptTemplate();

    expect(mocks.download).toHaveBeenCalledWith('/api/depts/import-template');
    // 返回的是"内容 + 文件名"，说明走的是带鉴权的下载接口，而非裸 URL
    expect(result).toEqual(PAYLOAD);
    expect(typeof result).not.toBe('string');
  });

  it('用户模板：同上（用户域）', async () => {
    const result = await downloadUserTemplate();

    expect(mocks.download).toHaveBeenCalledWith('/api/users/import-template');
    expect(result).toEqual(PAYLOAD);
  });
});

describe('导入请求形态（A2）', () => {
  it('部门导入：multipart + 字段名固定为 file + 未手工设置 Content-Type', async () => {
    const file = makeFile('部门.xlsx');
    await importDepts(file);

    expect(mocks.post).toHaveBeenCalledTimes(1);
    const [url, body, config] = mocks.post.mock.calls[0] as [string, FormData, unknown];

    expect(url).toBe('/api/depts/import');
    expect(body).toBeInstanceOf(FormData);
    // 字段名必须叫 file（写错后端收不到）
    expect(body.get('file')).toBe(file);
    // 不传 config → 不手工设置 Content-Type，交给浏览器带 boundary
    expect(config).toBeUndefined();
  });

  it('用户导入：同上（用户域）', async () => {
    const file = makeFile('用户.xlsx');
    await importUsers(file);

    const [url, body, config] = mocks.post.mock.calls[0] as [string, FormData, unknown];
    expect(url).toBe('/api/users/import');
    expect(body).toBeInstanceOf(FormData);
    expect(body.get('file')).toBe(file);
    expect(config).toBeUndefined();
  });
});

describe('导出请求（A11）', () => {
  it('部门导出：不带筛选时传 undefined（全量）', async () => {
    await exportDepts();

    expect(mocks.download).toHaveBeenCalledWith('/api/depts/export', undefined);
  });

  it('部门导出：带 status 筛选', async () => {
    await exportDepts({ status: 1 });

    expect(mocks.download).toHaveBeenCalledWith('/api/depts/export', { status: 1 });
  });

  it('用户导出：带上列表当前的 keyword / deptId（导出与所见一致）', async () => {
    await exportUsers({ keyword: '张', deptId: '2' });

    expect(mocks.download).toHaveBeenCalledWith('/api/users/export', { keyword: '张', deptId: '2' });
  });

  it('用户导出：无筛选时传 undefined', async () => {
    await exportUsers();

    expect(mocks.download).toHaveBeenCalledWith('/api/users/export', undefined);
  });
});
