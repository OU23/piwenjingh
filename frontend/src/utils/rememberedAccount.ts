/**
 * 「记住密码」本地存储
 *
 * ⚠️ 安全说明（用户已确认此取舍，2026-10-08）：
 * 本模块把账号与密码保存在浏览器 `localStorage`，目的仅是免去重复输入。
 * 这里做的只是**轻量混淆**（UTF-8 → Base64，避免明文直读），**不是加密、不构成安全存储**：
 * 无法抵御 XSS，也无法防护共用电脑上的读取。仅存于当前浏览器，不随账号同步到服务器。
 * 如需更高安全，应改用「只记住账号」或「延长会话有效期」方案。
 */
export const REMEMBERED_ACCOUNT_KEY = 'toolbox.remembered-account';

export interface RememberedAccount {
  username: string;
  password: string;
}

/** 轻量混淆：UTF-8 → Base64（非加密，勿当作安全措施） */
function obfuscate(text: string): string {
  try {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    bytes.forEach((byte) => {
      binary += String.fromCharCode(byte);
    });
    return btoa(binary);
  } catch {
    return '';
  }
}

function deobfuscate(encoded: string): string {
  try {
    const binary = atob(encoded);
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  } catch {
    return '';
  }
}

/** 保存（覆盖）记住的账号密码 */
export function saveRememberedAccount(username: string, password: string): void {
  try {
    localStorage.setItem(
      REMEMBERED_ACCOUNT_KEY,
      JSON.stringify({ u: obfuscate(username), p: obfuscate(password) }),
    );
  } catch {
    /* 隐私模式等禁用存储：忽略 */
  }
}

/** 读取记住的账号密码；无 / 损坏则返回 null */
export function readRememberedAccount(): RememberedAccount | null {
  try {
    const raw = localStorage.getItem(REMEMBERED_ACCOUNT_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as { u?: string; p?: string };
    const username = deobfuscate(parsed.u ?? '');
    const password = deobfuscate(parsed.p ?? '');
    if (!username) {
      return null;
    }
    return { username, password };
  } catch {
    return null;
  }
}

/** 清除记住的账号密码 */
export function clearRememberedAccount(): void {
  try {
    localStorage.removeItem(REMEMBERED_ACCOUNT_KEY);
  } catch {
    /* 忽略 */
  }
}
