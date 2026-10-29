import OSS from 'ali-oss';
import { post } from './request';
import {
  MAX_CONCURRENT_CHUNKS,
  UploadCanceledError,
  computeTotalChunks,
  type UploadProgress,
  type UploadResult,
  type UploadTask,
} from './chunkUpload';

/**
 * 浏览器直传 OSS（契约 v1.20 §8.6 / §8.7，M-23）
 *
 * 文件字节**由浏览器直接传给 OSS**，后端只签发临时凭证（STS）与完成登记复核。
 * 分片与断点续传由 `ali-oss` 的 `multipartUpload` 承担（分片上传，上限 48.8 TB → 消除 5 GB 天花板）。
 *
 * 三条硬约束：
 * 1. **只使用后端下发的临时凭证** —— 前端**绝不持有**长期 AK/SK（方案 §2.3）；
 * 2. **`partSize` 必须用后端回显值**（可能被自动上调，防 > 10000 片）；
 * 3. **凭证过期自动续传**：捕获 403 / `InvalidAccessKeyId` / `SecurityTokenExpired` →
 *    重新调 `credentials`（带 `objectKey` 复用）→ 新凭证重建客户端 → 带 `checkpoint` 继续，
 *    已完成分片**不重传**（用户无感）。
 */

const DIRECT_API = {
  credentials: '/api/upload/direct/credentials',
  complete: '/api/upload/direct/complete',
} as const;

/** 凭证过期后最多重取次数（防死循环） */
export const MAX_CREDENTIAL_RETRIES = 2;

/** 「分片上传会话已失效」后最多重发次数（丢弃断点记录、重新 initiate） */
export const MAX_STALE_UPLOAD_RETRIES = 1;

/** 断点续传记录在 localStorage 的键前缀（`File` 不可序列化，故此处不含 file） */
const CHECKPOINT_PREFIX = 'toolbox-direct-upload:';

export interface UploadCredentialValues {
  accessKeyId: string;
  accessKeySecret: string;
  securityToken: string;
}

/** 契约 v1.20 §8.6 `UploadCredentialVO` */
export interface UploadCredentialVO {
  objectKey: string;
  region: string;
  endpoint: string;
  bucket: string;
  credentials: UploadCredentialValues;
  expiration: string;
  /** 实际生效的分片大小（★ 可能被后端自动上调） */
  partSize: number;
  maxParts: number;
}

export interface UploadDirectCredentialDTO {
  fileName: string;
  fileSize: number;
  /** 续传时回传上次下发的对象键；首次上传不传 */
  objectKey?: string;
}

export interface UploadDirectCompleteDTO {
  objectKey: string;
  fileName: string;
  fileSize: number;
}

/** 请求直传临时凭证（`objectKey` 传入即为"续传复用"） */
export function requestCredential(data: UploadDirectCredentialDTO): Promise<UploadCredentialVO> {
  return post<UploadCredentialVO>(DIRECT_API.credentials, data);
}

/** 直传完成登记（出参 = `UploadMergeVO`，与 `merge` 同形，故复用 `UploadResult`） */
export function completeDirectUpload(data: UploadDirectCompleteDTO): Promise<UploadResult> {
  return post<UploadResult>(DIRECT_API.complete, data);
}

/** 断点续传记录（不含 `File`；`uploadId` + 已完成分片） */
export interface StoredCheckpoint {
  objectKey: string;
  uploadId: string;
  partSize: number;
  fileSize: number;
  doneParts: Array<{ number: number; etag: string }>;
}

function safeStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

export function readCheckpoint(identifier: string): StoredCheckpoint | null {
  const storage = safeStorage();
  const raw = storage?.getItem(CHECKPOINT_PREFIX + identifier);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as StoredCheckpoint;
    return parsed && parsed.uploadId && parsed.objectKey ? parsed : null;
  } catch {
    return null;
  }
}

export function saveCheckpoint(identifier: string, checkpoint: StoredCheckpoint): void {
  safeStorage()?.setItem(CHECKPOINT_PREFIX + identifier, JSON.stringify(checkpoint));
}

export function clearCheckpoint(identifier: string): void {
  safeStorage()?.removeItem(CHECKPOINT_PREFIX + identifier);
}

/** 判定是否为「凭证失效」类错误（据此自动重取凭证续传） */
export function isCredentialExpired(error: unknown): boolean {
  if (!error || typeof error !== 'object') {
    return false;
  }
  const e = error as { status?: number; code?: string; name?: string };
  if (e.status === 403) {
    return true;
  }
  const code = e.code ?? e.name ?? '';
  return ['InvalidAccessKeyId', 'SecurityTokenExpired', 'InvalidSecurityToken', 'ExpiredToken'].includes(code);
}

/**
 * 判定「该分片上传会话已不存在」——即 uploadId 已失效（被中止、或已合并完成）。
 *
 * <p>典型来源：**上一次分片已在 OSS 侧合并成功，但后端登记（`complete`）失败**
 * → 断点记录（含旧 `uploadId`）残留在 localStorage → 下次重试复用该 uploadId 直接合并
 * → OSS 返回 404 `The specified upload does not exist. The upload ID may be invalid,
 * or the upload may have been aborted or completed.` → **此后每次重试都 404（自锁）**。
 *
 * <p>识别到该错误时应**丢弃断点记录、重新发起一次**（见 {@link MAX_STALE_UPLOAD_RETRIES}）。
 */
export function isUploadGone(error: unknown): boolean {
  if (!error || typeof error !== 'object') {
    return false;
  }
  const e = error as { status?: number; code?: string; name?: string; message?: string };
  if (e.status === 404) {
    return true;
  }
  const code = e.code ?? e.name ?? '';
  if (code === 'NoSuchUpload') {
    return true;
  }
  // OSS 报文精确片段；比"含 does not exist"更窄，避免误伤其它 404 语义
  return typeof e.message === 'string' && e.message.includes('upload does not exist');
}

export interface DirectUploadTaskOptions {
  file: File;
  /** 文件标识（由 `createFileIdentifier` 预先算出）：断点续传记录与续传回传的依据 */
  identifier: string;
  /** 分片大小（字节）：取自 `/api/upload/mode` 的 `partSize` */
  partSize: number;
  onProgress?: (progress: UploadProgress) => void;
  /** 检查完成回调：用于界面提示"已从断点继续"（已传分片数） */
  onChecked?: (doneParts: number) => void;
}

/**
 * 直传任务：与 `ChunkUploadTask` **对外同形**（`UploadTask`），故上传页可无差别调度。
 *
 * 暂停语义：调 `client.cancel()` **中止在途分片**（点即停）；恢复时带 `checkpoint` 重新发起，
 * 已完成分片不重传（OSS 分片上传天然支持）。
 */
export class DirectUploadTask implements UploadTask {
  readonly identifier: string;

  private readonly file: File;
  private readonly initialPartSize: number;
  private readonly onProgress?: (progress: UploadProgress) => void;
  private readonly onChecked?: (doneParts: number) => void;

  /** 后端**回显**的生效分片大小（可能被上调），用于片数计算与展示 */
  private effectivePartSize: number;

  private canceled = false;
  private paused = false;
  private resumeWaiters: (() => void)[] = [];
  private client: OSS | null = null;
  private lastCredential: UploadCredentialVO | null = null;
  private objectKey: string | null = null;
  private completedParts = 0;
  private startedAt = 0;

  constructor(options: DirectUploadTaskOptions) {
    this.file = options.file;
    this.identifier = options.identifier;
    this.initialPartSize = options.partSize;
    this.effectivePartSize = options.partSize;
    this.onProgress = options.onProgress;
    this.onChecked = options.onChecked;
  }

  get totalChunks(): number {
    return computeTotalChunks(this.file.size, this.effectivePartSize);
  }

  async run(): Promise<UploadResult> {
    this.startedAt = Date.now();

    // 断点续传：按文件标识复用上次的 objectKey 与 checkpoint（分片大小/大小变了则作废）
    let checkpoint = this.restoreCheckpoint();
    this.objectKey = checkpoint?.objectKey ?? null;
    this.completedParts = checkpoint?.doneParts.length ?? 0;
    this.onChecked?.(this.completedParts);

    let credentialRetries = 0;
    let staleRetries = 0;

    for (;;) {
      if (this.canceled) {
        throw new UploadCanceledError();
      }
      if (this.paused) {
        await this.waitResume();
        continue;
      }

      const credential = await requestCredential({
        fileName: this.file.name,
        fileSize: this.file.size,
        ...(this.objectKey ? { objectKey: this.objectKey } : {}),
      });
      this.objectKey = credential.objectKey;
      this.lastCredential = credential;
      this.effectivePartSize = credential.partSize > 0 ? credential.partSize : this.initialPartSize;

      const client = this.createClient(credential);
      this.client = client;

      try {
        await client.multipartUpload(credential.objectKey, this.file, {
          partSize: this.effectivePartSize,
          parallel: MAX_CONCURRENT_CHUNKS,
          ...(checkpoint ? { checkpoint: toSdkCheckpoint(checkpoint, this.file) } : {}),
          progress: (percent: number, cpt: OSS.Checkpoint | null) => {
            const stored = toStoredCheckpoint(cpt, this.objectKey);
            if (stored) {
              checkpoint = stored;
              saveCheckpoint(this.identifier, stored);
              this.completedParts = stored.doneParts.length;
            }
            this.emitProgress(percent);
          },
        });
      } catch (error) {
        this.client = null;
        if (this.canceled) {
          throw new UploadCanceledError();
        }
        if (this.paused) {
          continue; // 暂停中止：交回循环顶部等待恢复，带 checkpoint 续传
        }
        if (isCredentialExpired(error) && credentialRetries < MAX_CREDENTIAL_RETRIES) {
          credentialRetries += 1;
          continue; // 凭证过期：重取凭证 + 复用 objectKey + 带 checkpoint 续传
        }
        if (isUploadGone(error) && staleRetries < MAX_STALE_UPLOAD_RETRIES) {
          // 该 uploadId 已失效（多为"上次分片已合并、但后端登记失败"留下的死记录）：
          // 丢弃断点记录并**重新发起一次**（新 initiate），否则用户会被永久锁在 404。
          // objectKey 仍复用之（同键重传，避免产生孤儿对象）。
          staleRetries += 1;
          clearCheckpoint(this.identifier);
          checkpoint = null;
          this.completedParts = 0;
          this.emitProgress(0);
          continue;
        }
        throw error;
      }
      this.client = null;
      this.completedParts = this.totalChunks;
      this.emitProgress(1);

      const merged = await completeDirectUpload({
        objectKey: credential.objectKey,
        fileName: this.file.name,
        fileSize: this.file.size,
      });
      clearCheckpoint(this.identifier);
      return { fileKey: merged.fileKey, fileName: merged.fileName, fileSize: merged.fileSize };
    }
  }

  /** 暂停：置标志并**中止在途分片**（ali-oss 要求用同一 client 实例 cancel） */
  pause(): void {
    this.paused = true;
    this.client?.cancel();
  }

  resume(): void {
    this.paused = false;
    this.releaseWaiters();
  }

  isPaused(): boolean {
    return this.paused;
  }

  /** 停止调度但**不清理**服务端分片（离开页面 / 刷新后可续传） */
  stop(): void {
    this.canceled = true;
    this.paused = false;
    this.releaseWaiters();
    this.client?.cancel();
  }

  /** 取消：停掉调度，并**尽力中止 OSS 会话**（删除已传分片）；失败不抛（由生命周期规则兜底） */
  async cancel(): Promise<void> {
    this.stop();
    const checkpoint = readCheckpoint(this.identifier);
    clearCheckpoint(this.identifier);
    const credential = this.lastCredential;
    if (checkpoint && credential) {
      try {
        await this.createClient(credential).abortMultipartUpload(checkpoint.objectKey, checkpoint.uploadId);
      } catch {
        // 中止失败无碍：Bucket 生命周期（1 天）会清理未完成分片
      }
    }
  }

  // ---------------- 内部 ----------------

  private restoreCheckpoint(): StoredCheckpoint | null {
    const stored = readCheckpoint(this.identifier);
    if (!stored) {
      return null;
    }
    // 文件大小或分片大小已变 → 旧记录的片号归属会错乱，直接作废
    // （两个都要比：只比 fileSize 会漏掉"分片大小被改过"的情形）
    if (stored.fileSize !== this.file.size || stored.partSize !== this.initialPartSize) {
      clearCheckpoint(this.identifier);
      return null;
    }
    return stored;
  }

  private createClient(credential: UploadCredentialVO): OSS {
    return new OSS({
      region: credential.region,
      endpoint: credential.endpoint,
      bucket: credential.bucket,
      accessKeyId: credential.credentials.accessKeyId,
      accessKeySecret: credential.credentials.accessKeySecret,
      stsToken: credential.credentials.securityToken,
      secure: true,
    });
  }

  private waitResume(): Promise<void> {
    if (!this.paused) {
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => {
      this.resumeWaiters.push(resolve);
    });
  }

  private releaseWaiters(): void {
    const waiters = this.resumeWaiters;
    this.resumeWaiters = [];
    for (const resolve of waiters) {
      resolve();
    }
  }

  /** 进度：`percent` 为 0~1 的比例（ali-oss 口径），换算为字节 / 片数 / 速度 */
  private emitProgress(percent: number): void {
    if (!this.onProgress) {
      return;
    }
    const ratio = Number.isFinite(percent) ? Math.min(1, Math.max(0, percent)) : 0;
    const totalChunks = this.totalChunks;
    const uploadedBytes = Math.round(ratio * this.file.size);
    const uploadedChunks = Math.max(this.completedParts, Math.round(ratio * totalChunks));
    const elapsedMs = Date.now() - this.startedAt;
    const speed = elapsedMs > 0 && uploadedBytes > 0 ? (uploadedBytes / elapsedMs) * 1000 : null;
    const remainingMs =
      speed && speed > 0 ? Math.max(0, Math.round(((this.file.size - uploadedBytes) / speed) * 1000)) : null;

    this.onProgress({
      uploadedBytes,
      totalBytes: this.file.size,
      uploadedChunks,
      totalChunks,
      speed,
      remainingMs,
    });
  }
}

/** 可序列化记录 → ali-oss 的 checkpoint（补回 `File`；`name` 即对象键） */
function toSdkCheckpoint(stored: StoredCheckpoint, file: File): OSS.Checkpoint {
  return {
    file,
    name: stored.objectKey,
    fileSize: stored.fileSize,
    partSize: stored.partSize,
    uploadId: stored.uploadId,
    doneParts: stored.doneParts,
  };
}

/** ali-oss 的 checkpoint → 可序列化记录（剔除 `File` 等不可序列化字段） */
function toStoredCheckpoint(cpt: OSS.Checkpoint | null | undefined, objectKey: string | null): StoredCheckpoint | null {
  if (!cpt || !cpt.uploadId || !objectKey) {
    return null;
  }
  return {
    objectKey,
    uploadId: cpt.uploadId,
    partSize: cpt.partSize,
    fileSize: cpt.fileSize,
    doneParts: (cpt.doneParts ?? []).map((part) => ({ number: part.number, etag: part.etag })),
  };
}
