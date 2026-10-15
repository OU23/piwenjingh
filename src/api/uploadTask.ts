import { ChunkUploadTask, type UploadProgress, type UploadTask } from './chunkUpload';
import type { UploadMode } from './uploadMode';

/**
 * 按上传模式创建上传任务（契约 v1.20 §8.5 / §8.6，M-23）
 *
 * 进入上传页时已由 `/api/upload/mode` 定好链路，**不允许"先试直传、失败再回退"**
 * （那会产生半截状态）：
 * - `direct` → 浏览器直传 OSS；模块**按需加载**（未启用直传时不引入 OSS SDK）；
 * - `server` → 原「检查 → 传分片 → 合并」链路。
 *
 * 两条链路对外同形（{@link UploadTask}），故上传页其余交互（进度 / 暂停 / 继续 / 取消）完全一致。
 */

export interface CreateUploadTaskOptions {
  mode: UploadMode;
  file: File;
  /** 文件标识（由 `createFileIdentifier` 预先算出）：断点续传记录的依据 */
  identifier: string;
  /** 分片大小（字节）：唯一来源是后端 `/api/upload/mode` 的 `partSize` */
  partSize: number;
  onProgress?: (progress: UploadProgress) => void;
  /** 检查完成回调：**已传分片数**（两条链路统一口径） */
  onChecked?: (uploadedChunks: number) => void;
}

export async function createUploadTask(options: CreateUploadTaskOptions): Promise<UploadTask> {
  const { file, identifier, partSize, onProgress, onChecked } = options;

  if (options.mode === 'direct') {
    const { DirectUploadTask } = await import('./directUpload');
    return new DirectUploadTask({
      file,
      identifier,
      partSize,
      onProgress,
      onChecked: (doneParts) => onChecked?.(doneParts),
    });
  }

  return new ChunkUploadTask({
    file,
    identifier,
    chunkSize: partSize,
    onProgress,
    onChecked: (uploadedChunks) => onChecked?.(uploadedChunks.length),
  });
}
