import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios';
import { BizCode, type ApiResult } from './types';

/**
 * 统一请求层（约定来源：docs/接口契约.md §1.1 / §1.3 / §1.4）
 * - 基址可配置（VITE_API_BASE_URL），不写死在源码里
 * - 业务结果以响应体 code 判定：HTTP 200 不代表成功
 * - 401 清登录态并跳登录页；403 就地提示，不跳转
 */

/** 业务失败（HTTP 200 + code ≠ 0） */
export class BizError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = 'BizError';
    this.code = code;
  }
}

/** 认证失败（HTTP 401） */
export class UnauthorizedError extends Error {
  readonly code = BizCode.UNAUTHORIZED;

  constructor(message = '登录状态已失效，请重新登录') {
    super(message);
    this.name = 'UnauthorizedError';
  }
}

/** 授权失败（HTTP 403） */
export class ForbiddenError extends Error {
  readonly code = BizCode.FORBIDDEN;

  constructor(message = '无操作权限') {
    super(message);
    this.name = 'ForbiddenError';
  }
}

interface RequestHooks {
  getToken: () => string | null;
  onUnauthorized: () => void;
}

/** 与登录态解耦：由 main.ts 注入，测试可替换为桩 */
const hooks: RequestHooks = {
  getToken: () => null,
  onUnauthorized: () => {},
};

export function configureRequest(next: Partial<RequestHooks>): void {
  Object.assign(hooks, next);
}

export const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 30_000,
});

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = hooks.getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * 网络层失败的兜底文案：
 * 请求根本没到达服务端（后端未启动 / 网络不通 / 跨域被拦）时，axios 只会给英文 "Network Error"，
 * 不得把这种原文抛给使用者。
 */
const NETWORK_ERROR_MESSAGE = '无法连接服务器，请确认后端服务已启动';
const TIMEOUT_ERROR_MESSAGE = '请求超时，请稍后重试';

function failureMessageOf(error: AxiosError<ApiResult>): string {
  const body = error.response?.data;

  if (body?.message) {
    return body.message;
  }
  if (error.response) {
    return `请求失败（HTTP ${error.response.status}）`;
  }
  return error.code === 'ECONNABORTED' ? TIMEOUT_ERROR_MESSAGE : NETWORK_ERROR_MESSAGE;
}

http.interceptors.response.use(
  (response: AxiosResponse<ApiResult>) => {
    const body = response.data;

    // 非统一响应体（如文件流）原样返回
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return response;
    }

    if (body.code !== BizCode.SUCCESS) {
      throw new BizError(body.code, body.message);
    }

    // 成功时只向调用方暴露 data
    return { ...response, data: body.data } as AxiosResponse;
  },
  (error: AxiosError<ApiResult>) => {
    const status = error.response?.status;
    const message = failureMessageOf(error);

    if (status === 401) {
      hooks.onUnauthorized();
      return Promise.reject(new UnauthorizedError(message));
    }
    if (status === 403) {
      return Promise.reject(new ForbiddenError(message));
    }

    return Promise.reject(new BizError(error.response?.data?.code ?? BizCode.SYSTEM_ERROR, message));
  },
);

async function requestData<T>(config: AxiosRequestConfig): Promise<T> {
  const response = (await http.request(config)) as AxiosResponse<unknown>;
  // 响应拦截器已把统一响应体解包为业务数据
  return response.data as T;
}

export function get<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig): Promise<T> {
  return requestData<T>({ method: 'GET', url, params, ...config });
}

export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return requestData<T>({ method: 'POST', url, data, ...config });
}

export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return requestData<T>({ method: 'PUT', url, data, ...config });
}

export function del<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig): Promise<T> {
  return requestData<T>({ method: 'DELETE', url, params, ...config });
}

export interface DownloadPayload {
  blob: Blob;
  fileName: string;
}

/**
 * 文件下载：带令牌请求后返回内容（禁止使用可直接访问的静态地址，见 principles.md R5）
 * 说明：GB 级下载的流式落盘在 P2 实现，本方法为统一入口
 */
export async function download(url: string, params?: Record<string, unknown>): Promise<DownloadPayload> {
  const response = await http.request<Blob>({ method: 'GET', url, params, responseType: 'blob' });
  const disposition = response.headers['content-disposition'] as string | undefined;
  return { blob: response.data, fileName: parseFileName(disposition) ?? 'download' };
}

/** 解析 Content-Disposition 中的文件名（契约 §9.5：中文名双写） */
export function parseFileName(disposition?: string | null): string | null {
  if (!disposition) {
    return null;
  }

  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return utf8Match[1];
    }
  }

  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition);
  return plainMatch?.[1] ?? null;
}

/** 触发浏览器保存（下载必须经接口拿到内容，不得用直链） */
export function saveBlob(blob: Blob, fileName: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(objectUrl);
}
