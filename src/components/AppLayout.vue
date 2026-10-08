<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import type { Component } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Box,
  Brush,
  CollectionTag,
  Delete,
  Expand,
  Fold,
  List,
  OfficeBuilding,
  Setting,
  Tools,
  Upload,
  User,
} from '@element-plus/icons-vue';
import { logout } from '@/api/auth';
import { MENU } from '@/constants/menu';
import { useUserStore } from '@/stores/user';
import { useThemeStore } from '@/stores/theme';
import { useLayoutStore } from '@/stores/layout';
import { filterMenuGroups } from '@/utils/permission';

/**
 * 全站布局骨架：左侧导航 + 顶栏（折叠开关 / 主题切换 / 用户 / 退出登录）+ 内容区。
 *
 * 侧栏菜单**采用 Element Plus 的 `<el-menu>` / `<el-sub-menu>`（与若依一致）**：
 *  - 展开/收起动画由 EP 组件自身负责（无需自己手写高度动画）；
 *  - `:unique-opened` = 手风琴（同时只展开一个分组）；
 *  - `:collapse-transition="false"` = 关闭折叠过渡；
 *  - 外层用 `<el-scrollbar>` 取代原生滚动条，避免滚动条出现/消失造成的横向重排抖动。
 */
const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const themeStore = useThemeStore();
const layoutStore = useLayoutStore();

const menuGroups = computed(() => filterMenuGroups(MENU, userStore.permissions));
const activeName = computed<string>(() => (typeof route.name === 'string' ? route.name : ''));

/**
 * 侧栏折叠/展开动画进行中标记。
 * 侧栏折叠是 width 动画；而苹果主题给侧栏挂了 backdrop-filter（毛玻璃），
 * 它按帧重算模糊 → 动画期间会掉帧。故动画期间让组件挂上 .is-animating，
 * 由主题 CSS 临时关闭模糊，动画结束再恢复（静态时不损失玻璃质感）。
 */
const asideAnimating = ref(false);
let asideAnimTimer: number | undefined;

watch(
  () => layoutStore.sidebarCollapsed,
  () => {
    asideAnimating.value = true;
    window.clearTimeout(asideAnimTimer);
    asideAnimTimer = window.setTimeout(() => {
      asideAnimating.value = false;
    }, 280); // 略大于 CSS 的 0.25s 过渡时长
  },
);

onBeforeUnmount(() => window.clearTimeout(asideAnimTimer));

/** 分组图标（按分组标题） */
const GROUP_ICONS: Record<string, Component> = {
  工具仓库: Box,
  系统管理: Setting,
};

/** 菜单项图标（按路由 name） */
const ITEM_ICONS: Record<string, Component> = {
  'tool-list': List,
  'tool-upload': Upload,
  'tool-deleted': Delete,
  'admin-user': User,
  'admin-dept': OfficeBuilding,
  'admin-tag': CollectionTag,
  'admin-setting': Tools,
};

const currentTitle = computed<string>(() => {
  const matched = MENU.flatMap((group) => group.items).find((item) => item.name === route.name);
  return matched?.title ?? '';
});

/** el-menu 选中项 = 路由 name，转成按名跳转 */
function onMenuSelect(name: string): void {
  void router.push({ name });
}

function onThemeChange(key: string): void {
  themeStore.setTheme(key);
}

async function handleLogout(): Promise<void> {
  // 令牌可能已失效：登出失败不阻断本地清理；记住的账号密码不在此清除（见 LoginView 文件头）
  await logout().catch(() => undefined);
  userStore.clear();
  await router.push({ name: 'login' });
}
</script>

<template>
  <div class="tb-layout">
    <aside
      class="tb-layout__aside"
      :class="{ 'is-collapsed': layoutStore.sidebarCollapsed, 'is-animating': asideAnimating }"
    >
      <div class="tb-layout__brand">tool-box</div>

      <div class="tb-layout__nav">
        <el-scrollbar>
          <el-menu
            class="tb-layout__menu"
            :default-active="activeName"
            :unique-opened="true"
            :collapse-transition="false"
            @select="onMenuSelect"
          >
            <el-sub-menu v-for="group in menuGroups" :key="group.title" :index="group.title">
              <template #title>
                <el-icon><component :is="GROUP_ICONS[group.title]" /></el-icon>
                <span>{{ group.title }}</span>
              </template>
              <el-menu-item v-for="item in group.items" :key="item.name" :index="item.name">
                <el-icon><component :is="ITEM_ICONS[item.name]" /></el-icon>
                <template #title>{{ item.title }}</template>
              </el-menu-item>
            </el-sub-menu>
          </el-menu>
        </el-scrollbar>
      </div>
    </aside>

    <div class="tb-layout__body">
      <header class="tb-layout__header">
        <div class="tb-layout__header-left">
          <button
            type="button"
            class="tb-layout__collapse"
            :title="layoutStore.sidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'"
            @click="layoutStore.toggleSidebar()"
          >
            <el-icon><component :is="layoutStore.sidebarCollapsed ? Expand : Fold" /></el-icon>
          </button>
          <div class="tb-layout__page-title">{{ currentTitle }}</div>
        </div>

        <div class="tb-layout__user">
          <el-icon class="tb-layout__theme-ico"><Brush /></el-icon>
          <el-select
            :model-value="themeStore.theme"
            size="small"
            class="tb-layout__theme"
            @change="onThemeChange"
          >
            <el-option
              v-for="t in themeStore.themes"
              :key="t.key"
              :label="t.label"
              :value="t.key"
            />
          </el-select>
          <span>{{ userStore.user?.realName ?? '—' }}</span>
          <span class="tb-muted">{{ userStore.user?.deptName ?? '' }}</span>
          <el-button size="small" @click="handleLogout">退出登录</el-button>
        </div>
      </header>

      <main class="tb-layout__main">
        <router-view v-slot="{ Component }">
          <transition name="fade-transform" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.tb-layout {
  display: flex;
  height: 100%;
  background: var(--tb-bg);
}

.tb-layout__aside {
  width: var(--tb-sidebar-width);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--tb-sider-bg);
  overflow: hidden;
  /* 侧栏右缘细线：浅色侧栏主题需要它分隔内容区；收起时透明以免留 1px 残线 */
  border-right: 1px solid transparent;
  transition: width 0.25s ease;
}

.tb-layout__aside:not(.is-collapsed) {
  border-right-color: var(--tb-sider-border);
}

.tb-layout__aside.is-collapsed {
  width: 0;
}

.tb-layout__brand {
  flex-shrink: 0;
  height: var(--tb-header-height);
  display: flex;
  align-items: center;
  padding: 0 var(--tb-gap-lg);
  background: var(--tb-brand-bg);
  color: var(--tb-brand-text);
  font-size: var(--tb-font-lg);
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.tb-layout__nav {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* 菜单深色适配（用 Element Plus 的菜单变量，随主题令牌走） */
.tb-layout__menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--tb-sider-text);
  --el-menu-hover-bg-color: var(--tb-sider-bg-hover);
  --el-menu-hover-text-color: var(--tb-sider-text-active);
  --el-menu-active-color: var(--tb-sider-text-active);
  --el-menu-item-height: 48px;
  --el-menu-sub-item-height: 44px;
  --el-menu-base-level-padding: var(--tb-gap);
  --el-menu-level-padding: 22px;
  border-right: none;
  padding: var(--tb-gap-xs) 0;
}

.tb-layout__menu :deep(.el-menu-item),
.tb-layout__menu :deep(.el-sub-menu__title) {
  font-size: var(--tb-font-sm);
}

.tb-layout__menu :deep(.el-sub-menu__title) {
  color: var(--tb-sider-group);
  font-size: var(--tb-font-xs);
}

.tb-layout__menu :deep(.el-sub-menu__title:hover) {
  color: var(--tb-sider-text-active);
}

.tb-layout__menu :deep(.el-menu-item.is-active) {
  background: var(--tb-sider-bg-active);
  border-left: 3px solid var(--tb-accent);
  color: var(--tb-sider-text-active);
  font-weight: 700;
}

.tb-layout__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.tb-layout__header {
  height: var(--tb-header-height);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tb-gap);
  padding: 0 var(--tb-gap-xl);
  background: var(--tb-panel);
  border-bottom: 1px solid var(--tb-border);
  z-index: var(--tb-z-header);
}

.tb-layout__header-left {
  display: flex;
  align-items: center;
  gap: var(--tb-gap);
  min-width: 0;
}

.tb-layout__collapse {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: var(--tb-text-2);
  border-radius: var(--tb-radius);
  cursor: pointer;
  transition:
    background 0.2s ease,
    color 0.2s ease;
}

.tb-layout__collapse:hover {
  background: var(--tb-row-hover);
  color: var(--tb-accent);
}

.tb-layout__page-title {
  font-size: var(--tb-font-lg);
  font-weight: 500;
  color: var(--tb-text);
}

.tb-layout__user {
  display: flex;
  align-items: center;
  gap: var(--tb-gap);
  font-size: var(--tb-font-sm);
}

.tb-layout__theme-ico {
  color: var(--tb-text-2);
}

.tb-layout__theme {
  width: 116px;
}

.tb-layout__main {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--tb-gap-xl);
}
</style>
