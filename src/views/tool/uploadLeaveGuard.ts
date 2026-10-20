import { onBeforeUnmount, onMounted } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessageBox } from 'element-plus';

/**
 * 上传中的"离开保护"（P20 / T4 从 `UploadView.vue` 抽出）
 *
 * - 浏览器刷新/关闭：`beforeunload` 弹原生确认；
 * - 路由离开：自定义确认框，确认后**主动停掉上传任务**（避免后台继续传）。
 *
 * 只负责"要不要走"，不做任何清理 —— 清理仍由页面自身的 `onBeforeUnmount` 处理。
 */
export interface UploadLeaveGuardOptions {
  /** 当前是否处于上传中（上传中才拦截） */
  isUploading: () => boolean;
  /** 确认离开时停掉在途任务 */
  stop: () => void;
}

export function useUploadLeaveGuard(options: UploadLeaveGuardOptions): void {
  function handleBeforeUnload(event: BeforeUnloadEvent): void {
    if (!options.isUploading()) {
      return;
    }
    event.preventDefault();
    event.returnValue = '';
  }

  onBeforeRouteLeave(async () => {
    if (!options.isUploading()) {
      return true;
    }
    try {
      await ElMessageBox.confirm(
        '正在上传，离开会中断本次上传（已传分片会保留，重新选择同一文件可续传）。确定离开吗？',
        '离开确认',
        { type: 'warning', confirmButtonText: '离开', cancelButtonText: '留在本页' },
      );
      options.stop();
      return true;
    } catch {
      return false;
    }
  });

  onMounted(() => window.addEventListener('beforeunload', handleBeforeUnload));
  onBeforeUnmount(() => window.removeEventListener('beforeunload', handleBeforeUnload));
}
