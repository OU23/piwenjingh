<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { logout } from '@/api/auth';
import { MENU } from '@/constants/menu';
import { useUserStore } from '@/stores/user';
import { filterMenuGroups } from '@/utils/permission';

/**
 * 全站布局骨架：左侧导航（按权限点渲染）+ 顶栏（当前用户 / 归属部门 / 退出登录）+ 内容区
 * 页面本体在 P2–P4 填充，本组件在 P1 定型，后续阶段只往里填页面。
 */
const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const menuGroups = computed(() => filterMenuGroups(MENU, userStore.permissions));

const currentTitle = computed<string>(() => {
  const matched = MENU.flatMap((group) => group.items).find((item) => item.name === route.name);
  return matched?.title ?? '';
});

async function handleLogout(): Promise<void> {
  // 令牌可能已失效：登出失败不阻断本地清理
  await logout().catch(() => undefined);
  userStore.clear();
  await router.push({ name: 'login' });
}
</script>

<template>
  <div class="tb-layout">
    <aside class="tb-layout__aside">
      <div class="tb-layout__brand">tool-box</div>

      <nav v-for="group in menuGroups" :key="group.title" class="tb-layout__group">
        <div class="tb-layout__group-title">{{ group.title }}</div>
        <router-link
          v-for="item in group.items"
          :key="item.name"
          class="tb-layout__item"
          :class="{ 'is-active': route.name === item.name }"
          :to="{ name: item.name }"
        >
          {{ item.title }}
        </router-link>
      </nav>
    </aside>

    <div class="tb-layout__body">
      <header class="tb-layout__header">
        <div class="tb-layout__page-title">{{ currentTitle }}</div>
        <div class="tb-layout__user">
          <span>{{ userStore.user?.realName ?? '—' }}</span>
          <span class="tb-muted">{{ userStore.user?.deptName ?? '' }}</span>
          <el-button link type="primary" @click="handleLogout">退出登录</el-button>
        </div>
      </header>

      <main class="tb-layout__main">
        <router-view />
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
  background: var(--tb-panel-3);
  border-right: 1px solid var(--tb-border);
  padding: var(--tb-gap-sm);
  overflow-y: auto;
}

.tb-layout__brand {
  font-size: var(--tb-font-lg);
  font-weight: 700;
  color: var(--tb-success);
  padding: var(--tb-gap-xs) var(--tb-gap-sm) var(--tb-gap);
}

.tb-layout__group-title {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
  padding: var(--tb-gap-sm) var(--tb-gap-sm) var(--tb-gap-xs);
}

.tb-layout__item {
  display: block;
  padding: 6px var(--tb-gap-sm);
  margin-bottom: 2px;
  border-radius: var(--tb-radius-sm);
  font-size: var(--tb-font-sm);
  color: var(--tb-text-2);
}

.tb-layout__item:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--tb-text);
}

.tb-layout__item.is-active {
  background: rgba(79, 140, 255, 0.15);
  color: var(--tb-accent);
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
  background: linear-gradient(135deg, var(--tb-panel), var(--tb-panel-2));
  border-bottom: 1px solid var(--tb-border);
  z-index: var(--tb-z-header);
}

.tb-layout__page-title {
  font-size: var(--tb-font-lg);
  font-weight: 500;
}

.tb-layout__user {
  display: flex;
  align-items: center;
  gap: var(--tb-gap);
  font-size: var(--tb-font-sm);
}

.tb-layout__main {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--tb-gap-xl);
}
</style>
