/**
 * 本地保存目标（下载落盘的位置）
 *
 * 与传输（src/api/download.ts）分开：本文件只管"写到哪、怎么续写"，
 * 传输只管"怎么取、取多少"。切换保存方式（如未来的目录授权）只改本文件。
 */

/** 单次写入通道（每次续传都重新打开） */
export interface SaveTargetWriter {
  write(chunk: Uint8Array): Promise<void>;
  seek(position: number): Promise<void>;
  /** 从头写时清掉旧文件尾部，避免同名旧文件残留 */
  truncate(size: number): Promise<void>;
  /** 关闭并落盘 */
  close(): Promise<void>;
  /** 放弃本次写入（会丢弃未落盘内容） */
  abort(): Promise<void>;
}

/** 保存目标：跨多次会话保留，用于续传 */
export interface SaveTarget {
  open(): Promise<SaveTargetWriter>;
}

export interface SaveTargetProvider {
  readonly supported: boolean;
  /** 让用户选择保存位置；返回 null 表示用户取消 */
  pick(suggestedName: string): Promise<SaveTarget | null>;
}

interface FileHandleLike {
  createWritable(options?: { keepExistingData?: boolean }): Promise<SaveTargetWriter>;
}

type SaveFilePickerLike = (options?: { suggestedName?: string }) => Promise<FileHandleLike>;

function getSaveFilePicker(): SaveFilePickerLike | null {
  const picker = (globalThis as { showSaveFilePicker?: SaveFilePickerLike }).showSaveFilePicker;
  return typeof picker === 'function' ? picker : null;
}

/** 浏览器实现的保存目标（File System Access API）；不支持时 supported 为 false */
export const fileSystemProvider: SaveTargetProvider = {
  get supported(): boolean {
    return getSaveFilePicker() !== null;
  },

  async pick(suggestedName: string): Promise<SaveTarget | null> {
    const picker = getSaveFilePicker();
    if (!picker) {
      return null;
    }

    let handle: FileHandleLike;
    try {
      handle = await picker({ suggestedName });
    } catch (error) {
      // 用户在系统保存框中取消：不是错误，按"未开始"处理
      if (error instanceof DOMException && error.name === 'AbortError') {
        return null;
      }
      throw error;
    }

    return {
      open: () => handle.createWritable({ keepExistingData: true }),
    };
  },
};

/** 可续传记录（按 工具 + 版本 保存：同一文件再次点击时接着上次位置写） */
export interface ResumeRecord {
  target: SaveTarget;
  written: number;
  total: number | null;
}

const resumeRecords = new Map<string, ResumeRecord>();

export function getResume(key: string): ResumeRecord | null {
  return resumeRecords.get(key) ?? null;
}

export function setResume(key: string, record: ResumeRecord): void {
  resumeRecords.set(key, record);
}

export function clearResume(key: string): void {
  resumeRecords.delete(key);
}

/** 清空全部续传记录（页面级收尾与测试隔离使用） */
export function clearAllResume(): void {
  resumeRecords.clear();
}
