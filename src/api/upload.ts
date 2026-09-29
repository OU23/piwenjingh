import { del, post } from './request';

/**
 * 文件上传（docs/接口契约.md §八）
 * 字段名必须与契约完全一致，不得自行改名；分片与合并协议由前后端共同遵守。
 */
const UPLOAD_API = {
  check: '/api/upload/check',
  chunk: '/api/upload/chunk',
  merge: '/api/upload/merge',
  cancel: (identifier: string) => `/api/upload/${encodeURIComponent(identifier)}`,
} as const;

export interface UploadCheckDTO {
  identifier: string;
  fileName: string;
  fileSize: number;
  totalChunks: number;
}

export interface UploadCheckVO {
  /** 已成功接收的分片序号（从 1 开始）；无则为空数组 */
  uploadedChunks: number[];
}

export interface UploadMergeDTO {
  identifier: string;
  fileName: string;
  fileSize: number;
  totalChunks: number;
}

export interface UploadMergeVO {
  /** 文件标识，创建工具 / 版本时回传；不体现物理路径 */
  fileKey: string;
  fileName: string;
  fileSize: number;
}

export interface UploadChunkForm {
  chunk: Blob;
  identifier: string;
  /** 当前分片序号，从 1 开始 */
  chunkNumber: number;
  totalChunks: number;
  fileName: string;
}

export interface UploadChunkOptions {
  /** 分片自身进度（0–100），用于合并出整体进度 */
  onProgress?: (percent: number) => void;
  /** 中止信号：取消上传时必须能停掉在途分片请求（P3 T5） */
  signal?: AbortSignal;
}

/** 上传前检查：返回已接收的分片序号，是断点续传的依据 */
export function checkUpload(data: UploadCheckDTO): Promise<UploadCheckVO> {
  return post<UploadCheckVO>(UPLOAD_API.check, data);
}

/** 上传单个分片（multipart/form-data，字段名照契约 §八） */
export function uploadChunk(form: UploadChunkForm, options: UploadChunkOptions = {}): Promise<null> {
  const payload = new FormData();
  payload.append('chunk', form.chunk);
  payload.append('identifier', form.identifier);
  payload.append('chunkNumber', String(form.chunkNumber));
  payload.append('totalChunks', String(form.totalChunks));
  payload.append('fileName', form.fileName);

  return post<null>(UPLOAD_API.chunk, payload, {
    signal: options.signal,
    onUploadProgress: (event) => {
      if (options.onProgress && event.total) {
        options.onProgress(Math.round((event.loaded / event.total) * 100));
      }
    },
  });
}

/** 合并分片，返回文件标识 */
export function mergeUpload(data: UploadMergeDTO): Promise<UploadMergeVO> {
  return post<UploadMergeVO>(UPLOAD_API.merge, data);
}

/** 取消上传：清理该次已传分片 */
export function cancelUpload(identifier: string): Promise<null> {
  return del<null>(UPLOAD_API.cancel(identifier));
}
