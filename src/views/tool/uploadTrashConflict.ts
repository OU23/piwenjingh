import { ElMessageBox } from 'element-plus';
import { purgeTools, restoreTool } from '@/api/tool';
import type { ID } from '@/api/types';
import type { ToolMetaValues } from '@/utils/toolMetaValidation';

/**
 * 回收站同名冲突处理（契约 v1.21 §9.4）
 *
 * - 「恢复旧工具」→ 恢复后再次提交即走追加版本；
 * - 「删除旧工具」→ 走 `batch-purge`（需 `tool:delete`；无该权限时**不提供**该选项，避免点了 403）。
 *
 * 从 `UploadView.vue` 抽出（P20 / T4）：纯交互 + 接口调用，不持有页面状态；
 * 处理结果通过 `setMessage` 写回页面（成功提示与失败提示同一入口）。
 */
export interface TrashConflictOptions {
  /** 是否可彻底删除（`tool:delete`） */
  canPurge: boolean;
  /** 把结果文案写回页面（成功 / 失败都走这里） */
  setMessage: (message: string) => void;
}

export async function resolveTrashConflict(
  outcome: { toolId: ID | null },
  values: Pick<ToolMetaValues, 'name'>,
  options: TrashConflictOptions,
): Promise<void> {
  if (!outcome.toolId) {
    options.setMessage('回收站里已有同名工具，请先到「回收站」处理后再提交。');
    return;
  }

  let action: 'restore' | 'purge';
  try {
    await ElMessageBox.confirm(
      options.canPurge
        ? `回收站里已有你上传的同名工具「${values.name}」。恢复它可继续追加为新版本；彻底删除它可释放名称、重新创建。`
        : `回收站里已有你上传的同名工具「${values.name}」，请先恢复它（继续追加版本）后再提交。`,
      '回收站已有同名工具',
      options.canPurge
        ? {
            type: 'warning',
            confirmButtonText: '恢复旧工具',
            cancelButtonText: '删除旧工具',
            distinguishCancelAndClose: true,
          }
        : { type: 'info', confirmButtonText: '恢复旧工具', showCancelButton: false },
    );
    action = 'restore';
  } catch (err) {
    if (err === 'cancel' && options.canPurge) {
      action = 'purge';
    } else {
      return; // 关闭弹窗 = 不做处理
    }
  }

  try {
    if (action === 'purge') {
      await purgeTools([outcome.toolId]);
      options.setMessage('已彻底删除旧工具，请再次点击「提交」以创建新工具。');
      return;
    }
    await restoreTool(outcome.toolId);
    options.setMessage('旧工具已恢复，请再次点击「提交」以追加为新版本。');
  } catch (error) {
    options.setMessage(error instanceof Error ? error.message : '处理旧工具失败，请稍后重试');
  }
}
