import type { SettingSaveDTO, SettingVO } from '@/api/setting';

/**
 * 全局设置的取值与校验（纯函数，便于单测）
 * 规则来源：docs/接口契约.md §十
 * - 存储根路径：管理员**手动输入**的绝对路径（不提供目录选择器）——服务端可能为 Linux，无本地盘可浏览
 *   · **统一使用正斜杠 `/`**（v1.5）：Windows 的反斜杠 `\` 在保存前归一化
 * - 单文件上限：界面按 GB 填写，契约按**字节**传输
 * - 保存为**增量**：与当前值一致的不下发
 */

export const BYTES_PER_GB = 1024 ** 3;
export const BYTES_PER_MB = 1024 ** 2;

/** 单文件上限的允许范围（GB）：下限 1 GB，上限 1024 GB（1 TB） */
export const FILE_SIZE_LIMIT_GB = { min: 1, max: 1024 } as const;

/**
 * 分片大小的允许范围（MB，契约 v1.20 §十）：5 ~ 100 MB。
 * ⚠️ 与后端 `SettingService.MIN_CHUNK_SIZE / MAX_CHUNK_SIZE` 保持一致；
 * 后端才是权威（越界 → 1003），此处仅用于即时提示。
 */
export const CHUNK_SIZE_MB = { min: 5, max: 100 } as const;

/** 路径归一化：去首尾空格 + 反斜杠转正斜杠（契约 §十 v1.5：统一用 `/`） */
export function normalizeStorageRoot(path: string): string {
  return path.trim().replace(/\\/g, '/');
}

export interface SettingFormValues {
  storageRoot: string;
  /** 界面填写值，单位 GB */
  maxFileSizeGb: number;
  /**
   * 分片大小（界面填写值，单位 MB）。
   * **选填**：未提供视为"不修改该项"（与 DTO 的"选填、传了才改"语义一致）。
   */
  chunkSizeMb?: number;
}

/** 当前设置 → 表单值（字节换算为 GB / MB；路径归一化为 `/`） */
export function toSettingFormValues(settings: SettingVO): SettingFormValues {
  return {
    // 无 sys:setting 权限时后端返回 null（补充说明 N-03）：此处退化为空串，由界面提示无权查看
    storageRoot: settings.storageRoot === null ? '' : normalizeStorageRoot(settings.storageRoot),
    maxFileSizeGb: Math.round((settings.maxFileSize / BYTES_PER_GB) * 10) / 10,
    chunkSizeMb: Math.round(settings.chunkSize / BYTES_PER_MB),
  };
}

/** 绝对路径判定：Linux 以 `/` 开头；Windows 以盘符加分隔符开头 */
export function isAbsolutePath(path: string): boolean {
  const value = normalizeStorageRoot(path);
  return value.startsWith('/') || /^[A-Za-z]:\//.test(value);
}

/** 返回第一条不满足的提示；通过返回空字符串 */
export function validateSettingForm(values: SettingFormValues): string {
  const root = normalizeStorageRoot(values.storageRoot);

  if (!root) {
    return '请输入存储根路径';
  }
  if (!isAbsolutePath(root)) {
    return '存储根路径需为绝对路径，例如 Linux：/data/toolbox/files，Windows：D:/toolbox/files';
  }
  if (!Number.isFinite(values.maxFileSizeGb) || values.maxFileSizeGb < FILE_SIZE_LIMIT_GB.min) {
    return `单文件上限不能小于 ${FILE_SIZE_LIMIT_GB.min} GB`;
  }
  if (values.maxFileSizeGb > FILE_SIZE_LIMIT_GB.max) {
    return `单文件上限不能大于 ${FILE_SIZE_LIMIT_GB.max} GB`;
  }
  if (values.chunkSizeMb !== undefined) {
    if (!Number.isFinite(values.chunkSizeMb) || values.chunkSizeMb < CHUNK_SIZE_MB.min) {
      return `分片大小不能小于 ${CHUNK_SIZE_MB.min} MB`;
    }
    if (values.chunkSizeMb > CHUNK_SIZE_MB.max) {
      return `分片大小不能大于 ${CHUNK_SIZE_MB.max} MB`;
    }
  }
  return '';
}

/** 只提交变更项（契约 §十：至少传一项，传了才改）；路径下发前统一归一化为 `/` */
export function buildSettingSaveDTO(current: SettingVO, next: SettingFormValues): SettingSaveDTO {
  const dto: SettingSaveDTO = {};

  const root = normalizeStorageRoot(next.storageRoot);
  const currentRoot = current.storageRoot === null ? null : normalizeStorageRoot(current.storageRoot);
  if (root && root !== currentRoot) {
    dto.storageRoot = root;
  }

  const bytes = Math.round(next.maxFileSizeGb * BYTES_PER_GB);
  if (bytes > 0 && bytes !== current.maxFileSize) {
    dto.maxFileSize = bytes;
  }

  if (next.chunkSizeMb !== undefined) {
    const chunkBytes = Math.round(next.chunkSizeMb * BYTES_PER_MB);
    if (chunkBytes > 0 && chunkBytes !== current.chunkSize) {
      dto.chunkSize = chunkBytes;
    }
  }

  return dto;
}

/** 是否有实际改动（无改动不应发起请求） */
export function hasSettingChanges(dto: SettingSaveDTO): boolean {
  return dto.storageRoot !== undefined || dto.maxFileSize !== undefined || dto.chunkSize !== undefined;
}

/** 保存确认文案（必须含"不会自动迁移"） */
export function settingConfirmMessage(hasRootChange: boolean): string {
  if (!hasRootChange) {
    return '确定保存全局设置吗？新的单文件上限与分片大小将用于之后的上传（进行中的上传不受影响）。';
  }
  return '确定修改存储根路径吗？⚠️ 变更前已上传的文件不会自动迁移，需要人工把旧目录下的文件拷贝到新目录，否则这些工具将无法下载。';
}
