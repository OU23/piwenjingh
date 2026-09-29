import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { ID } from '@/api/types';
import type { UploadResult } from '@/api/chunkUpload';
import type { ToolMetaMode, ToolMetaValues } from '@/utils/toolMetaValidation';

export type UploadStep = 'pick' | 'upload' | 'meta' | 'done';

export interface UploadDraft {
  mode: ToolMetaMode;
  toolId?: ID;
  toolName: string;
  step: UploadStep;
  uploadResult: UploadResult | null;
  meta: Partial<ToolMetaValues> | null;
}

/**
 * 未完成上传的记录。
 *
 * 用途**仅限**：刷新页面后，在选择文件步骤提示"上次上传未完成，重新选择同一文件可从断点继续"。
 *
 * ⚠️ 不做自动续传：`File` 对象无法序列化（浏览器安全限制），必须由用户重新选择文件。
 * 服务端按**文件标识**保留已收分片，重新选择同一文件时 `check` 会返回已传分片，
 * 因此"重新选择即续传"是天然的，本记录只负责把这件事告诉用户。
 */
export interface PendingUpload {
  fileName: string;
  fileSize: number;
  totalChunks: number;
  /** 服务端已接收的分片数（仅用于提示文案） */
  uploadedChunks: number;
}

const DRAFT_KEY = 'toolbox.upload-draft';
const PENDING_KEY = 'toolbox.upload-pending';

function read<T>(key: string): T | null {
  try {
    const raw = sessionStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function write(key: string, value: unknown | null): void {
  try {
    if (value) {
      sessionStorage.setItem(key, JSON.stringify(value));
    } else {
      sessionStorage.removeItem(key);
    }
  } catch {
    /* sessionStorage 不可用时忽略：仅影响草稿续填，不阻断上传 */
  }
}

/**
 * 上传草稿：离开 /upload 路由（侧边栏切换）或刷新页面后，若处于"填写信息"步骤且文件已上传完成，
 * 可凭此草稿续填，无需重新上传（文件已落服务端，持 fileKey 即可提交）。
 * 进行中的上传（step=upload）不续填——File 对象无法序列化；改用 `pendingUpload` 给提示。
 *
 * ⚠️ 两种记录必须**成对清理**，否则会出现"上一轮已放弃、刷新后却被旧记录劫持"的问题：
 * 放弃填写 / 开始新上传 / 提交成功 / 离开页面时，都要清掉对应记录。
 */
export const useUploadDraftStore = defineStore('upload-draft', () => {
  const draft = ref<UploadDraft | null>(read<UploadDraft>(DRAFT_KEY));
  const pendingUpload = ref<PendingUpload | null>(read<PendingUpload>(PENDING_KEY));

  function save(next: UploadDraft): void {
    draft.value = next;
    write(DRAFT_KEY, next);
  }

  function clear(): void {
    draft.value = null;
    write(DRAFT_KEY, null);
  }

  function isResumable(): boolean {
    return !!draft.value && draft.value.step === 'meta' && !!draft.value.uploadResult;
  }

  function savePending(next: PendingUpload): void {
    pendingUpload.value = next;
    write(PENDING_KEY, next);
  }

  function clearPending(): void {
    pendingUpload.value = null;
    write(PENDING_KEY, null);
  }

  return { draft, pendingUpload, save, clear, isResumable, savePending, clearPending };
});
