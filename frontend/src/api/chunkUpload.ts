import { cancelUpload, checkUpload, mergeUpload, uploadChunk } from './upload';

/**
 * 分片上传调度（协议见 docs/接口契约.md §八）
 *
 * 三条硬约束：
 * 1. **字段名照契约**（`chunk` / `identifier` / `chunkNumber` / `totalChunks` / `fileName`），不自行改名；
 * 2. **不读整文件入内存**：只用 `File.slice` 取单片（principles.md R4，验收 A9）；
 * 3. **断点续传**：以 `check` 返回的已传分片为准，**只补传缺失分片**。
 *
 * 调度策略：单文件串行上传，分片并发 `MAX_CONCURRENT_CHUNKS`，单片失败按次数上限重试并退避。
 * 暂停语义：`pause()` **中止全部在途分片**（点即停），被中止的片无损回到队首、恢复后重传。
 */

/**
 * 分片并发数（固定常量，不写裸数字）。
 *
 * ⚠️ 分片大小**前端不再有默认值 / 常量**：唯一来源是后端 `/api/upload/mode` 的 `partSize`
 * （即全局设置 `chunkSize`，默认 20 MB）—— 避免"设置页一个值、上传时另一个值"（M-23 方案 §5.6）。
 */
export const MAX_CONCURRENT_CHUNKS = 3;

/** 单个分片的最大重试次数（首次之外） */
export const MAX_CHUNK_RETRIES = 2;

/** 重试退避基数：第 n 次重试等待 n × 该值 */
export const RETRY_BACKOFF_MS = 500;

export interface UploadProgress {
  uploadedBytes: number;
  totalBytes: number;
  uploadedChunks: number;
  totalChunks: number;
  /** 平均速度（字节/秒）；样本不足时为 null */
  speed: number | null;
  /** 预计剩余毫秒；无法估计时为 null */
  remainingMs: number | null;
}

export interface UploadResult {
  fileKey: string;
  fileName: string;
  fileSize: number;
}

/**
 * 上传任务的统一接口（M-23）：`ChunkUploadTask`（经服务器）与 `DirectUploadTask`（直传 OSS）
 * 对外**同形** —— 上传页按 `mode` 二选一后，其余交互（进度 / 暂停 / 继续 / 取消）完全一致。
 */
export interface UploadTask {
  readonly identifier: string;
  readonly totalChunks: number;
  run(): Promise<UploadResult>;
  pause(): void;
  resume(): void;
  isPaused(): boolean;
  stop(): void;
  cancel(): Promise<void>;
}

/** 用户主动取消（区别于失败：调用方不应提示"重试"） */
export class UploadCanceledError extends Error {
  constructor() {
    super('上传已取消');
    this.name = 'UploadCanceledError';
  }
}

/** 总片数 = 向上取整（契约 §8.3）；`chunkSize` 由调用方传入（取自后端 partSize） */
export function computeTotalChunks(fileSize: number, chunkSize: number): number {
  if (fileSize <= 0 || chunkSize <= 0) {
    return 0;
  }
  return Math.ceil(fileSize / chunkSize);
}

/** 取第 chunkNumber 片（序号从 1 开始）；只切该片，不读整文件 */
export function sliceChunk(file: Blob, chunkNumber: number, chunkSize: number): Blob {
  const start = (chunkNumber - 1) * chunkSize;
  return file.slice(start, start + chunkSize);
}

/** 缺失分片序号：从 1 开始，排除已传（断点续传的核心） */
export function pickPendingChunks(uploadedChunks: readonly number[], totalChunks: number): number[] {
  const uploaded = new Set(uploadedChunks);
  const pending: number[] = [];

  for (let chunkNumber = 1; chunkNumber <= totalChunks; chunkNumber += 1) {
    if (!uploaded.has(chunkNumber)) {
      pending.push(chunkNumber);
    }
  }

  return pending;
}

/** 采样分段大小：头 / 中 / 尾各取该字节数（合计 ≤ 768 KB，**不读整文件**） */
export const SAMPLE_SEGMENT_SIZE = 256 * 1024;

/** 读取 Blob 字节：优先原生 `arrayBuffer()`，环境不支持时退回 `FileReader`（如 jsdom） */
async function readBlobBytes(blob: Blob): Promise<ArrayBuffer> {
  if (typeof blob.arrayBuffer === 'function') {
    return blob.arrayBuffer();
  }

  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error ?? new Error('读取文件采样失败'));
    reader.readAsArrayBuffer(blob);
  });
}

/** 取文件头 / 中 / 尾的采样字节；小文件直接整读（≤ 3 段） */
async function sampleBytes(file: File): Promise<Uint8Array> {
  const segment = SAMPLE_SEGMENT_SIZE;
  const ranges: Array<[number, number]> =
    file.size <= segment * 3
      ? [[0, file.size]]
      : [
          [0, segment],
          [Math.floor(file.size / 2) - Math.floor(segment / 2), segment],
          [file.size - segment, segment],
        ];

  const parts = await Promise.all(ranges.map(([start, length]) => readBlobBytes(file.slice(start, start + length))));
  const merged = new Uint8Array(parts.reduce((sum, part) => sum + part.byteLength, 0));
  let offset = 0;

  for (const part of parts) {
    merged.set(new Uint8Array(part), offset);
    offset += part.byteLength;
  }

  return merged;
}

/** 采样哈希：SHA-256 优先（取前 16 位十六进制），环境不支持时退化为 FNV-1a */
async function sampleHash(file: File): Promise<string> {
  const bytes = await sampleBytes(file);

  if (typeof crypto !== 'undefined' && crypto.subtle) {
    try {
      const digest = await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource);
      return Array.from(new Uint8Array(digest).slice(0, 8))
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('');
    } catch {
      // 落到下面的退化分支
    }
  }

  let hash = 0x811c9dc5;
  for (let index = 0; index < bytes.length; index += 1) {
    hash ^= bytes[index];
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

/**
 * 文件标识（契约 §8.3 + `接口补充说明.md` N-05）
 *
 * 组成：`tbf-<采样哈希>-<文件大小>`
 * - 采样哈希 = 文件**头 / 中 / 尾**各 256 KB 的 SHA-256 前 16 位（环境不支持时退化 FNV-1a）；
 * - 同一文件重复上传得到**相同标识** → 断点续传与分片归属的依据；
 * - 文件被改动 → 采样内容或大小变化 → 标识随之变化，**不会错误复用旧分片**；
 * - **不读整文件**：GB 级文件也只读 ≤ 768 KB（principles.md R4）。
 *
 * ⚠️ 后端把该值当作**不透明字符串**，不得校验格式。
 */
export async function createFileIdentifier(file: File): Promise<string> {
  return `tbf-${await sampleHash(file)}-${file.size}`;
}

export interface ChunkUploadTaskOptions {
  file: File;
  /** 文件标识（由 `createFileIdentifier` 预先异步算出后传入，见契约 §8.3） */
  identifier: string;
  onProgress?: (progress: UploadProgress) => void;
  /** 检查完成回调：用于界面提示"已从断点继续" */
  onChecked?: (uploadedChunks: number[]) => void;
  /** 分片大小（字节）；**必填** —— 唯一来源是后端 `/api/upload/mode` 的 `partSize` */
  chunkSize: number;
}

export class ChunkUploadTask implements UploadTask {
  readonly identifier: string;
  readonly totalChunks: number;

  private readonly file: File;
  private readonly chunkSize: number;
  private readonly onProgress?: (progress: UploadProgress) => void;
  private readonly onChecked?: (uploadedChunks: number[]) => void;

  private uploadedChunks: number[] = [];
  private uploadedBytes = 0;
  private paused = false;
  private canceled = false;
  private startedAt = 0;
  /**
   * 暂停等待者队列。
   * ⚠️ 必须是**队列**而非单槽：并发 worker 最多 `MAX_CONCURRENT_CHUNKS` 个，单槽会被后注册者覆盖，
   * 导致恢复时只有最后一个被唤醒、其余 worker 永久阻塞（`drain()` 的 `Promise.all` 永不结算）。
   */
  private resumeWaiters: (() => void)[] = [];
  /** 在途分片请求的控制器集合（并发下同时有多片在传，暂停需全部中止） */
  private inflight = new Set<AbortController>();

  constructor(options: ChunkUploadTaskOptions) {
    this.file = options.file;
    this.chunkSize = options.chunkSize;
    this.onProgress = options.onProgress;
    this.onChecked = options.onChecked;
    this.identifier = options.identifier;
    this.totalChunks = computeTotalChunks(options.file.size, this.chunkSize);
  }

  /** 检查 → 传缺失分片 → 合并 */
  async run(): Promise<UploadResult> {
    this.startedAt = Date.now();

    const checked = await checkUpload({
      identifier: this.identifier,
      fileName: this.file.name,
      fileSize: this.file.size,
      totalChunks: this.totalChunks,
    });

    this.uploadedChunks = [...(checked.uploadedChunks ?? [])];
    this.uploadedBytes = this.bytesOf(this.uploadedChunks);
    // 传出副本：避免外部保存后被内部后续 push 意外改写
    this.onChecked?.([...this.uploadedChunks]);
    this.emitProgress();

    await this.drain(pickPendingChunks(this.uploadedChunks, this.totalChunks));

    if (this.canceled) {
      throw new UploadCanceledError();
    }

    const merged = await mergeUpload({
      identifier: this.identifier,
      fileName: this.file.name,
      fileSize: this.file.size,
      totalChunks: this.totalChunks,
    });

    this.uploadedBytes = this.file.size;
    this.emitProgress();

    return { fileKey: merged.fileKey, fileName: merged.fileName, fileSize: merged.fileSize };
  }

  /**
   * 暂停：置标志并**中止在途分片请求**（点即停，不等当前分片传完）。
   *
   * 被中止的分片未上传成功，`worker` 会把它放回队首，恢复后重传；
   * 契约 §8.3 明确后端接受并发与乱序，因此重传同一分片是安全的。
   */
  pause(): void {
    this.paused = true;
    this.abortInflight();
  }

  resume(): void {
    this.paused = false;
    this.releaseWaiters();
  }

  isPaused(): boolean {
    return this.paused;
  }

  /**
   * 停止本次上传调度，但**不清理**服务端已传分片 —— 用于离开页面/刷新，
   * 保留"重选同一文件可续传"的可能（区别于 cancel()）。
   */
  stop(): void {
    this.canceled = true;
    this.paused = false;
    this.releaseWaiters();
    this.abortInflight();
  }

  /** 取消：停掉在途与未开始的分片，并通知服务端清理已传分片（契约 §8.4） */
  async cancel(): Promise<void> {
    this.stop();
    await cancelUpload(this.identifier).catch(() => undefined);
  }

  private async drain(queue: number[]): Promise<void> {
    const workerCount = Math.min(MAX_CONCURRENT_CHUNKS, queue.length);
    const workers = Array.from({ length: workerCount }, () => this.worker(queue));
    await Promise.all(workers);
  }

  private async worker(queue: number[]): Promise<void> {
    for (;;) {
      if (this.canceled) {
        return;
      }
      if (this.paused) {
        await this.waitResume();
        continue;
      }

      const chunkNumber = queue.shift();
      if (chunkNumber === undefined) {
        return;
      }

      const outcome = await this.uploadOne(chunkNumber);

      if (outcome === 'canceled') {
        return;
      }
      if (outcome === 'paused') {
        queue.unshift(chunkNumber); // 该片未传成功：放回队首，恢复后重传（不丢片）
        await this.waitResume();
      }
    }
  }

  private waitResume(): Promise<void> {
    if (!this.paused) {
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => {
      this.resumeWaiters.push(resolve);
    });
  }

  /** 唤醒全部暂停等待者（并发 worker 可能同时停在暂停点上，必须一个不漏） */
  private releaseWaiters(): void {
    const waiters = this.resumeWaiters;
    this.resumeWaiters = [];
    for (const resolve of waiters) {
      resolve();
    }
  }

  /** 中止全部在途分片请求（取消 / 暂停共用） */
  private abortInflight(): void {
    const controllers = [...this.inflight];
    this.inflight.clear();
    for (const controller of controllers) {
      controller.abort();
    }
  }

  /**
   * 上传单片的最终结果：
   * - `done`：已成功上传；
   * - `paused`：被暂停中止（未成功，需由 worker 重新入队，恢复后重传）；
   * - `canceled`：本次调度已取消。
   */
  private async uploadOne(chunkNumber: number): Promise<'done' | 'paused' | 'canceled'> {
    const chunk = sliceChunk(this.file, chunkNumber, this.chunkSize);

    for (let attempt = 0; ; attempt += 1) {
      if (this.canceled) {
        return 'canceled';
      }

      const controller = new AbortController();
      this.inflight.add(controller);

      try {
        await uploadChunk(
          {
            chunk,
            identifier: this.identifier,
            chunkNumber,
            totalChunks: this.totalChunks,
            fileName: this.file.name,
          },
          { signal: controller.signal },
        );

        this.uploadedChunks.push(chunkNumber);
        this.uploadedBytes += chunk.size;
        this.emitProgress();
        this.inflight.delete(controller);
        return 'done';
      } catch (error) {
        this.inflight.delete(controller);

        if (this.canceled) {
          return 'canceled';
        }
        // 暂停中止产生的异常（请求层会包成"网络失败"文案）不是真失败：
        // 不重试、不计入重试次数，交回 worker 无损重新入队。
        if (this.paused) {
          return 'paused';
        }
        if (attempt >= MAX_CHUNK_RETRIES) {
          throw error;
        }
        await delay(RETRY_BACKOFF_MS * (attempt + 1));
      }
    }
  }

  private bytesOf(chunks: readonly number[]): number {
    return chunks.reduce((sum, chunkNumber) => sum + sliceChunk(this.file, chunkNumber, this.chunkSize).size, 0);
  }

  private emitProgress(): void {
    if (!this.onProgress) {
      return;
    }

    const elapsedMs = Date.now() - this.startedAt;
    const speed = elapsedMs > 0 && this.uploadedBytes > 0 ? (this.uploadedBytes / elapsedMs) * 1000 : null;
    const remainingMs =
      speed && speed > 0 ? Math.max(0, Math.round(((this.file.size - this.uploadedBytes) / speed) * 1000)) : null;

    this.onProgress({
      uploadedBytes: this.uploadedBytes,
      totalBytes: this.file.size,
      uploadedChunks: this.uploadedChunks.length,
      totalChunks: this.totalChunks,
      speed,
      remainingMs,
    });
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}
