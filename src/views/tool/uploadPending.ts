import type { UploadProgress } from '@/api/chunkUpload';
import { useUploadDraftStore } from '@/stores/uploadDraft';

/**
 * 「未完成上传」进度记录器（P20 / T4 从 `UploadView.vue` 抽出）
 *
 * 只写 sessionStorage、不参与调度：`File` 无法序列化，刷新后仍需用户重新选择同一文件；
 * 服务端按文件标识保留分片，重新选择即自动续传（见 `stores/uploadDraft.ts`）。
 *
 * 同一进度不重复写：分片回调很密集，避免每片都落一次盘。
 */
export interface PendingProgressRecorder {
  record: (fileName: string, progress: UploadProgress) => void;
  reset: () => void;
}

export function createPendingProgressRecorder(): PendingProgressRecorder {
  let savedChunks = -1;

  return {
    record(fileName: string, progress: UploadProgress): void {
      if (progress.totalChunks <= 0 || progress.uploadedChunks === savedChunks) {
        return;
      }
      savedChunks = progress.uploadedChunks;
      useUploadDraftStore().savePending({
        fileName,
        fileSize: progress.totalBytes,
        totalChunks: progress.totalChunks,
        uploadedChunks: progress.uploadedChunks,
      });
    },
    reset(): void {
      savedChunks = -1;
    },
  };
}
