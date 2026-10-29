import { ElMessageBox } from 'element-plus';
import type { Router } from 'vue-router';
import { ws, type SessionEndReason } from '@/api/ws';
import { useUserStore } from '@/stores/user';

/**
 * 会话终结处理（M-18 被踢 / **M-21 权限变更**；契约 §3.3）。
 *
 * 触发源（两者的**幂等**由 `api/ws.ts` 保证：同一连接生命周期内只上报一次）：
 * - `SESSION_REPLACED`：WS 推送 `FORCE_LOGOUT`，或连接以关闭码 `4002` 结束；
 * - `PERMISSION_CHANGED`：WS 推送 `PERMISSION_CHANGED`，或连接以关闭码 `4004` 结束
 *   （**M-21 / 契约 §3.3 v1.12** —— 管理员变更该账号权限，旧会话已被服务端终结）。
 *
 * 需求口径（需求方明确）：弹窗**只有「知道了」一个按钮**，
 * **不得**通过 X / 遮罩 / ESC 绕过；确认后清登录态并以 `replace` 回登录页
 * （`replace` 避免用户按返回键回到已登录页面）。
 */

/** 各类会话终结的文案 */
export const SESSION_END_TEXT: Record<SessionEndReason, { title: string; message: string }> = {
  SESSION_REPLACED: {
    title: '下线通知',
    message: '你的账号在另一台设备上登录，您即将下线。',
  },
  PERMISSION_CHANGED: {
    title: '权限变更',
    message: '账号发生异常，请重新登录',
  },
};

/** 兼容既有引用（P7 交付时已对外暴露；取"被踢"文案） */
export const FORCE_LOGOUT_TITLE = SESSION_END_TEXT.SESSION_REPLACED.title;
export const FORCE_LOGOUT_MESSAGE = SESSION_END_TEXT.SESSION_REPLACED.message;

/**
 * 处理会话终结：弹通知 → 断开连接 → 清登录态 → 回登录页。
 *
 * @param reason 终结来源；**缺省按"被踢"处理**（兼容既有调用方）
 */
export async function handleSessionKicked(
  router: Router,
  reason: SessionEndReason = 'SESSION_REPLACED',
): Promise<void> {
  const text = SESSION_END_TEXT[reason] ?? SESSION_END_TEXT.SESSION_REPLACED;

  await ElMessageBox.alert(text.message, text.title, {
    confirmButtonText: '知道了',
    showCancelButton: false, // ★ 只有一个按钮
    showClose: false, // ★ 不允许点 X
    closeOnClickModal: false, // ★ 不允许点遮罩
    closeOnPressEscape: false, // ★ 不允许 ESC
  }).catch(() => undefined);

  ws.disconnect();
  useUserStore().clear();
  await router.replace({ name: 'login' });
}
