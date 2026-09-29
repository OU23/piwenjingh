/**
 * 测试范围：请求层失败文案（src/api/request.ts）——网络不可达 / 超时 / 无 body 的兜底
 * 测试类型：单元
 * 测试目的：任何失败都必须给**中文可读**提示，不得把 axios / fetch 的英文原文抛给使用者
 * 防回归问题：后端未启动时界面显示 "Network Error"、"Failed to fetch" 之类的技术原文
 * 关键不变量：
 *   - 无响应（未到达服务端）→ 「无法连接服务器，请确认后端服务已启动」
 *   - 超时 → 「请求超时，请稍后重试」
 *   - 有响应但无 message → 「请求失败（HTTP <状态码>）」
 *   - 后端给了 message → 原样使用（不覆盖业务可读原因）
 * 边界说明：不覆盖真实网络；下载路径的同类兜底见 DownloadButton（另有测试）
 * 依赖与限制：用桩替换 axios adapter，不发起真实请求
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { configureRequest, get, http } from '@/api/request';

function throwError(message: string, code?: string): AxiosAdapter {
  return async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    throw new AxiosError(message, code, config, null, undefined);
  };
}

function failWith(status: number, body: unknown): AxiosAdapter {
  return async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const response = { data: body, status, statusText: 'ERROR', headers: {}, config } as AxiosResponse;
    throw new AxiosError('request failed', String(status), config, null, response);
  };
}

beforeEach(() => {
  configureRequest({ getToken: () => null, onUnauthorized: vi.fn() });
});

afterEach(() => {
  configureRequest({ getToken: () => null, onUnauthorized: () => undefined });
});

describe('请求层失败文案', () => {
  it('后端未启动（请求未到达服务端）给中文提示，而不是 Network Error', async () => {
    http.defaults.adapter = throwError('Network Error', 'ERR_NETWORK');

    const error = await get('/api/tools').catch((reason: unknown) => reason);

    expect(error).toMatchObject({ message: '无法连接服务器，请确认后端服务已启动' });
    expect((error as Error).message).not.toContain('Network Error');
  });

  it('请求超时给专门的提示', async () => {
    http.defaults.adapter = throwError('timeout of 30000ms exceeded', 'ECONNABORTED');

    await expect(get('/api/tools')).rejects.toMatchObject({ message: '请求超时，请稍后重试' });
  });

  it('有响应但无 message 时按状态码给提示', async () => {
    http.defaults.adapter = failWith(502, 'Bad Gateway');

    await expect(get('/api/tools')).rejects.toMatchObject({ message: '请求失败（HTTP 502）' });
  });

  it('后端给了可读 message 时原样使用', async () => {
    http.defaults.adapter = failWith(500, { code: 1000, message: '系统繁忙，请稍后重试', data: null });

    await expect(get('/api/tools')).rejects.toMatchObject({ message: '系统繁忙，请稍后重试' });
  });
});
