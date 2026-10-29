/** 展示格式化（不承载业务规则） */

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const;
const UNIT_STEP = 1024;
const DECIMAL_DIGITS = 1;

/** 文件大小：字节 → 可读文本（契约 §1.6：大小统一按字节传输） */
export function formatFileSize(bytes?: number | null): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes) || bytes < 0) {
    return '—';
  }
  if (bytes === 0) {
    return '0 B';
  }

  let value = bytes;
  let unitIndex = 0;
  while (value >= UNIT_STEP && unitIndex < BYTE_UNITS.length - 1) {
    value /= UNIT_STEP;
    unitIndex += 1;
  }

  const digits = unitIndex === 0 ? 0 : DECIMAL_DIGITS;
  return `${value.toFixed(digits)} ${BYTE_UNITS[unitIndex]}`;
}

/** 时间：后端已按 yyyy-MM-dd HH:mm:ss 返回（契约 §1.6），此处只做空值兜底 */
export function formatDateTime(value?: string | null): string {
  return value ? value : '—';
}

/** 传输速度：字节/秒 → 可读文本（样本不足时显示 —） */
export function formatSpeed(bytesPerSecond?: number | null): string {
  if (
    bytesPerSecond === null ||
    bytesPerSecond === undefined ||
    !Number.isFinite(bytesPerSecond) ||
    bytesPerSecond <= 0
  ) {
    return '—';
  }
  return `${formatFileSize(bytesPerSecond)}/s`;
}

/** 剩余时长：毫秒 → 可读文本（如「2 分 30 秒」），无法估计时显示 — */
export function formatDuration(milliseconds?: number | null): string {
  if (milliseconds === null || milliseconds === undefined || !Number.isFinite(milliseconds) || milliseconds <= 0) {
    return '—';
  }

  const totalSeconds = Math.ceil(milliseconds / 1000);
  if (totalSeconds < 60) {
    return `${totalSeconds} 秒`;
  }

  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) {
    return seconds > 0 ? `${minutes} 分 ${seconds} 秒` : `${minutes} 分`;
  }

  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
}
