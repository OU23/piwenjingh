/**
 * Base64 ↔ 二进制的小工具（纯函数，便于单测）
 *
 * 用途：导入失败时，后端把「错误明细 Excel」以 `errorFileBase64` **内联**返回
 * （免临时文件 / 免二次取回），前端只需把它还原成 Blob 交给既有落盘函数。
 *
 * ⚠️ 边界：**只做字节搬运，不解析 Excel 内容**（前端不参与 Excel 解析，见 `plan/P8` §9）；
 * 也不打印任何文件内容到日志（见 `rules/02-规范细则/security/security.md`）。
 */

/** xlsx 的 MIME（与契约 §6.2 文件下载响应的 `Content-Type` 一致） */
export const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/**
 * 把 Base64 字符串还原为二进制 Blob。
 * 入参为空或非法 Base64 时返回 `null`（调用方据此提示"解析失败，请重试"，而不是抛异常）。
 */
export function base64ToBlob(base64: string, mimeType: string = XLSX_MIME): Blob | null {
  const normalized = base64.replace(/\s/g, '');
  if (!normalized) {
    return null;
  }

  try {
    const binary = atob(normalized);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return new Blob([bytes], { type: mimeType });
  } catch {
    return null;
  }
}
