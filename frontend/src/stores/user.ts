import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { CurrentUserVO } from '@/api/auth';
import { hasPermission } from '@/utils/permission';

const TOKEN_KEY = 'toolbox.token';
const USER_KEY = 'toolbox.user';

function readToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) ?? '';
  } catch {
    // 本地存储不可用（隐私模式等）：按未登录处理
    return '';
  }
}

function readUser(): CurrentUserVO | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? (JSON.parse(raw) as CurrentUserVO) : null;
  } catch {
    return null;
  }
}

/**
 * 登录态（令牌 + 当前用户 + 权限点）
 * 前端显隐依赖权限点，但安全最终依据在后端（principles.md R5）。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(readToken());
  const user = ref<CurrentUserVO | null>(readUser());
  /** 本地存储一旦写失败就不再重试，避免每次操作都抛错 */
  const storageWritable = ref(true);

  const permissions = computed<string[]>(() => user.value?.permissions ?? []);
  const isLoggedIn = computed<boolean>(() => token.value.length > 0);

  function persist(): void {
    if (!storageWritable.value) {
      return;
    }
    try {
      if (token.value) {
        localStorage.setItem(TOKEN_KEY, token.value);
      } else {
        localStorage.removeItem(TOKEN_KEY);
      }

      if (user.value) {
        localStorage.setItem(USER_KEY, JSON.stringify(user.value));
      } else {
        localStorage.removeItem(USER_KEY);
      }
    } catch {
      storageWritable.value = false;
    }
  }

  /** 登录成功后写入令牌与用户信息 */
  function setLogin(payload: { token: string; user: CurrentUserVO }): void {
    token.value = payload.token;
    user.value = payload.user;
    persist();
  }

  /** 刷新用户信息（如权限调整后） */
  function setUser(next: CurrentUserVO): void {
    user.value = next;
    persist();
  }

  /** 清理全部认证状态（登出 / 令牌失效） */
  function clear(): void {
    token.value = '';
    user.value = null;
    persist();
  }

  /** 是否具备某权限点（不传 = 登录即可） */
  function has(code?: string | null): boolean {
    return hasPermission(permissions.value, code ?? null);
  }

  return { token, user, permissions, isLoggedIn, setLogin, setUser, clear, has };
});
