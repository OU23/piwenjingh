/**
 * 测试范围：主题 store（src/stores/theme.ts）
 * 测试类型：单元（jsdom）
 * 测试目的：保证主题切换的状态、<html> 应用与持久化行为正确
 * 防回归问题：切换不生效、不写 localStorage、非法值不回落
 * 关键不变量：
 *   - 默认主题为 DEFAULT_THEME
 *   - setTheme 更新 state + 写 <html data-theme> + 持久化
 *   - 非法主题回落到默认值
 * 边界说明：不验证 CSS 实际渲染（jsdom 不跑样式）
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import {
  applyTheme,
  DEFAULT_THEME,
  readStoredTheme,
  THEME_STORAGE_KEY,
  useThemeStore,
} from '@/stores/theme';

describe('主题 store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    document.documentElement.classList.remove('dark');
  });

  it('默认主题为 ruoyi-blue，且主题清单非空', () => {
    const store = useThemeStore();
    expect(store.theme).toBe(DEFAULT_THEME);
    expect(store.themes.length).toBeGreaterThan(0);
  });

  it('setTheme 更新状态、应用到 <html> 并持久化', () => {
    const store = useThemeStore();
    store.setTheme(DEFAULT_THEME);

    expect(store.theme).toBe(DEFAULT_THEME);
    expect(document.documentElement.getAttribute('data-theme')).toBe(DEFAULT_THEME);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe(DEFAULT_THEME);
    // 浅色主题不应挂 dark 类
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('非法主题回落到默认值', () => {
    const store = useThemeStore();
    store.setTheme('not-exist');
    expect(store.theme).toBe(DEFAULT_THEME);
  });

  it('readStoredTheme 有值读值、无值读默认', () => {
    expect(readStoredTheme()).toBe(DEFAULT_THEME);
    localStorage.setItem(THEME_STORAGE_KEY, DEFAULT_THEME);
    expect(readStoredTheme()).toBe(DEFAULT_THEME);
    localStorage.setItem(THEME_STORAGE_KEY, 'unknown');
    expect(readStoredTheme()).toBe(DEFAULT_THEME);
  });

  it('applyTheme 按 dark 标记挂/去 .dark 类', () => {
    applyTheme(DEFAULT_THEME); // 当前唯一主题 dark=false
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });
});
