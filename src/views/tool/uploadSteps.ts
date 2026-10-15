import type { UploadProgress } from '@/api/chunkUpload';

/** 上传流程步骤（选择文件 → 上传 → 填写信息 → 完成） */
export type StepName = 'pick' | 'upload' | 'meta' | 'done';

export const STEPS: { key: StepName; title: string }[] = [
  { key: 'pick', title: '选择文件' },
  { key: 'upload', title: '上传' },
  { key: 'meta', title: '填写信息' },
  { key: 'done', title: '完成' },
];

/** 空进度（未开始 / 已重置时使用） */
export function emptyProgress(totalBytes = 0): UploadProgress {
  return { uploadedBytes: 0, totalBytes, uploadedChunks: 0, totalChunks: 0, speed: null, remainingMs: null };
}
