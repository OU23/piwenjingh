/**
 * 测试范围：统一请求层（src/api/request.ts）——业务码判定、401 / 403 分支、令牌注入、Content-Disposition 解析
 * 测试类型：单元
 * 测试目的：锁死"HTTP 200 不等于成功"与 401 / 403 的行为（契约 §1.3）
 * 防回归问题：把业务失败当成功、401 不清登录态、403 误跳登录页、令牌未随请求携带
 * 关键不变量：
 *   - 响应体 code !== 0 → 抛 BizError（携带业务码与可读 message）
 *   - 401 → 调用 onUnauthorized 且抛 UnauthorizedError
 *   - 403 → 抛 ForbiddenError，且不触发 onUnauthorized
 * 边界说明：不覆盖真实网络传输、GB 级下载的流式落盘（P2 / P5）
 * 依赖与限制：用桩替换 axios adapter，不发起真实请求
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import {
  BizError,
  ForbiddenError,
  UnauthorizedError,
  configureRequest,
  get,
  http,
  parseFileName,
} from '@/api/request';
import { BizCode } from '@/api/types';

function respondWith(body: unknown, status = 200): AxiosAdapter {
  return async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => ({
    data: body,
    status,
    statusText: 'OK',
    headers: {},
    config,
  });
}

function failWith(status: number, body: unknown): AxiosAdapter {
  return async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const response = { data: body, status, statusText: 'ERROR', headers: {}, config } as AxiosResponse;
    throw new AxiosError('request failed', String(status), config, null, response);
  };
}

let onUnauthorized: ReturnType<typeof vi.fn>;

beforeEach(() => {
  onUnauthorized = vi.fn();
  configureRequest({ getToken: () => null, onUnauthorized });
});

afterEach(() => {
  configureRequest({ getToken: () => null, onUnauthorized: () => undefined });
});

describe('统一请求层', () => {
  it('code 为 0 时返回业务数据', async () => {
    http.defaults.adapter = respondWith({ code: 0, message: 'ok', data: { id: '1001' } });

    await expect(get<{ id: string }>('/api/tools/1001')).resolves.toEqual({ id: '1001' });
  });

  it('HTTP 200 但业务码非 0 时按失败处理，并带上业务码与提示', async () => {
    http.defaults.adapter = respondWith({ code: BizCode.TOOL_NOT_FOUND, message: '该工具不存在或已被删除', data: null });

    const error = await get('/api/tools/1001').catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(BizError);
    expect(error).toMatchObject({ code: BizCode.TOOL_NOT_FOUND, message: '该工具不存在或已被删除' });
  });

  it('HTTP 401 时清理登录态并抛出未登录错误', async () => {
    http.defaults.adapter = failWith(401, {
      code: BizCode.UNAUTHORIZED,
      message: '登录状态已失效，请重新登录',
      data: null,
    });

    const error = await get('/api/tools').catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(UnauthorizedError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('HTTP 403 时抛出无权限错误，且不跳转登录页', async () => {
    http.defaults.adapter = failWith(403, { code: BizCode.FORBIDDEN, message: '无操作权限', data: null });

    await expect(get('/api/tools')).rejects.toBeInstanceOf(ForbiddenError);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('HTTP 500 时按系统异常处理', async () => {
    http.defaults.adapter = failWith(500, { code: BizCode.SYSTEM_ERROR, message: '系统异常', data: null });

    await expect(get('/api/tools')).rejects.toMatchObject({ code: BizCode.SYSTEM_ERROR });
  });

  it('登录后请求自动携带 Authorization', async () => {
    let authorization: unknown;
    http.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
      authorization = config.headers.Authorization;
      return { data: { code: 0, message: 'ok', data: null }, status: 200, statusText: 'OK', headers: {}, config };
    };
    configureRequest({ getToken: () => 'token-value' });

    await get('/api/auth/me');

    expect(authorization).toBe('Bearer token-value');
  });
});

describe('下载文件名解析', () => {
  it('优先取 UTF-8 编码的中文名', () => {
    const disposition = "attachment; filename=\"log.zip\"; filename*=UTF-8''%E6%97%A5%E5%BF%97.zip";

    expect(parseFileName(disposition)).toBe('日志.zip');
  });

  it('仅有普通文件名时取普通名', () => {
    expect(parseFileName('attachment; filename="tool.zip"')).toBe('tool.zip');
  });

  it('缺少响应头时返回 null', () => {
    expect(parseFileName(undefined)).toBeNull();
  });
});
