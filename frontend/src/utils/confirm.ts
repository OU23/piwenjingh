import { ElMessageBox } from 'element-plus';

/**
 * 不可逆操作的二次确认（规范要求：删除 / 停用 / 清空权限 / 修改存储路径等必须确认）
 * 用户取消时返回 false，不抛异常，便于页面写 `if (!(await confirmAction(...))) return;`
 */
export async function confirmAction(message: string, title = '请确认', confirmText = '确定'): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
      type: 'warning',
    });
    return true;
  } catch {
    return false;
  }
}
