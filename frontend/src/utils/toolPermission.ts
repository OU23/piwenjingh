import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import type { ID } from '@/api/types';

/**
 * 「可编辑该工具」判权（与后端 `ToolWriteService.requireEditable` **同口径**）：
 * 上传人本人 / 双权限管理员（`user:manage` + `user:grant`，**AND**）/ 全局管理员（`sys:setting`）。
 *
 * 抽取原因（M-25）：列表行、详情页、编辑页三处都要判断，口径必须一致；
 * 前端只用于入口显隐，后端判权仍是权威（principles.md R5）。
 */
export function useToolEditPermission(): (tool: { uploaderId?: ID | null } | null | undefined) => boolean {
  const userStore = useUserStore();

  return (tool) => {
    if (!tool) {
      return false;
    }
    if (userStore.has(PERMISSION.SYS_SETTING)) {
      return true;
    }
    if (userStore.has(PERMISSION.USER_MANAGE) && userStore.has(PERMISSION.USER_GRANT)) {
      return true;
    }
    const me = userStore.user?.userId;
    return me != null && tool.uploaderId != null && String(tool.uploaderId) === String(me);
  };
}
