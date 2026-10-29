import { formatFileSize } from './format';

/**
 * 选取文件的预校验（纯函数，便于单测）
 * 口径：只拦"确定无法上传"的情况；**文件名不做长度 / 字符校验**——
 * 中文、特殊字符、较长文件名都属正常（见 P3 验收 C2：不得因此报错）。
 */

/** 返回第一条不满足的提示；通过返回空字符串 */
export function validatePickedFile(file: File, maxSize: number): string {
  if (file.size <= 0) {
    return '文件为空，请重新选择';
  }
  if (maxSize > 0 && file.size > maxSize) {
    return `文件大小 ${formatFileSize(file.size)} 超过上限 ${formatFileSize(maxSize)}，请调整后再上传`;
  }
  if (!file.name.trim()) {
    return '文件名无效，请重新选择文件';
  }
  return '';
}

/**
 * Excel 导入文件的**单文件大小上限**（契约 §6.2：**10 MB**）。
 * ⚠️ 这是契约固定值，与上传工具的 `maxFileSize`（后端可配）无关。
 */
export const IMPORT_FILE_MAX_SIZE = 10 * 1024 * 1024;

/** Excel 导入文件的后缀（契约 §6.2：**仅 `.xlsx`**，`.xls` / `.csv` 不支持） */
export const IMPORT_FILE_EXTENSION = '.xlsx';

/**
 * Excel 导入文件的预校验（纯函数，前端**先拦一遍**，不发无谓请求）。
 * 判定顺序：后缀 → 空文件 → 大小；返回第一条不满足的提示，通过返回空字符串。
 *
 * 说明：**只按后缀判定，不看 `File.type`** —— 浏览器给的 MIME 在不同系统上可能为空或不准，
 * 照 MIME 判断会把正常文件误拦；真正的类型/内容合法性由后端把关（契约 §6.2 → `1003`）。
 */
export function validateImportFile(file: File, maxSize: number = IMPORT_FILE_MAX_SIZE): string {
  if (!file.name.trim().toLowerCase().endsWith(IMPORT_FILE_EXTENSION)) {
    return `仅支持 ${IMPORT_FILE_EXTENSION} 文件（Excel 2007 及以上格式），请重新选择`;
  }
  if (file.size <= 0) {
    return '文件为空，请重新选择';
  }
  if (maxSize > 0 && file.size > maxSize) {
    return `文件大小 ${formatFileSize(file.size)} 超过上限 ${formatFileSize(maxSize)}，请调整后再导入`;
  }
  return '';
}
