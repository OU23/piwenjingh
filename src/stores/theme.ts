import { defineStore } from 'pinia';
import { ref } from 'vue';

/** 主题定义：key 需与 themes/<key>.css 的选择器 html[data-theme="<key>"] 一致 */
export interface ThemeDef {
  key: string;
  label: string;
  /** 是否暗色系：决定是否为 <html> 挂 .dark 类（让 Element Plus 暗色变量生效） */
  dark: boolean;
}

/**
 * 主题清单。
 * 本期仅一套（若依经典·蓝）；新增主题只需：① 加一个 themes/<key>.css；② 在此追加一项。
 * 组件与样式无需任何改动 —— 「一份主题 CSS = 一次全局换肤」。
 */
export const THEMES: ThemeDef[] = [{ key: 'ruoyi-blue', label: '若依蓝', dark: false }];

/** localStorage 键名 */
export const THEME_STORAGE_KEY = 'toolbox-theme';
/** 默认主题 key */
export const DEFAULT_THEME = 'ruoyi-blue';

/** 解析主题：非法/缺失一律回落到默认主题 */
export function resolveTheme(key: string | null | undefined): ThemeDef {
  return THEMES.find((t) => t.key === key) ?? (THEMES.find((t) => t.key === DEFAULT_THEME) as ThemeDef);
}

/** 把主题应用到 <html>（data-theme + 明暗类）并持久化 */
export function applyTheme(key: string): void {
  const def = resolveTheme(key);
  const root = document.documentElement;
  root.setAttribute('data-theme', def.key);
  root.classList.toggle('dark', def.dark);
  try {
    localStorage.setItem(THEME_STORAGE_KEY, def.key);
  } catch {
    /* 隐私模式等禁用存储的场景：忽略持久化失败，不影响本次应用 */
  }
}

/** 读取已保存主题（无/非法则默认），供启动时先应用、避免首屏闪烁 */
export function readStoredTheme(): string {
  try {
    return resolveTheme(localStorage.getItem(THEME_STORAGE_KEY)).key;
  } catch {
    return DEFAULT_THEME;
  }
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<string>(readStoredTheme());
  const themes = THEMES;

  function setTheme(key: string): void {
    theme.value = resolveTheme(key).key;
    applyTheme(theme.value);
  }

  return { theme, themes, setTheme };
});
