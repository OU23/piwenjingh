import { get } from './request';

/**
 * 上传模式与分片参数（契约 v1.20 §8.5，M-23）
 *
 * 前端**进入上传页第一步**调用一次，据此决定走哪条链路——
 * 不允许"先试直传、失败再回退"（会产生半截状态）。
 * 同时它把**分片参数与上限的唯一来源**收敛到后端，前端不得写死。
 */

/** 上传通道：`direct`（浏览器直传 OSS）/ `server`（经服务器中转） */
export type UploadMode = 'direct' | 'server';

export interface UploadModeVO {
  mode: UploadMode;
  /** 生效的分片大小（字节） */
  partSize: number;
  /** 分片大小允许下限（字节）：5 MB */
  partSizeMin: number;
  /** 分片大小允许上限（字节）：100 MB */
  partSizeMax: number;
  /** 单文件上限（字节） */
  maxFileSize: number;
}

/** 查询当前上传模式与分片参数 */
export function getUploadMode(): Promise<UploadModeVO> {
  return get<UploadModeVO>('/api/upload/mode');
}
