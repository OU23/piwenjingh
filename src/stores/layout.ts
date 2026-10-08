import { defineStore } from 'pinia';
import { ref } from 'vue';

/** localStorage 键名 */
export const SIDEBAR_COLLAPSED_KEY = 'toolbox-sidebar-collapsed';

/** 读取已保存的侧边栏折叠状态（默认展开） */
export function readSidebarCollapsed(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * 布局 store：侧边栏折叠状态。
 * 状态持久化到 localStorage，刷新后保持（AppLayout 在 setup 阶段同步读取，首屏即正确、无闪烁）。
 */
export const useLayoutStore = defineStore('layout', () => {
  const sidebarCollapsed = ref<boolean>(readSidebarCollapsed());

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value;
    try {
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, sidebarCollapsed.value ? '1' : '0');
    } catch {
      /* 隐私模式等禁用存储的场景：忽略持久化失败，不影响本次切换 */
    }
  }

  return { sidebarCollapsed, toggleSidebar };
});
