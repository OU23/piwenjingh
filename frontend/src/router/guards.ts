import type { Router } from 'vue-router';
import { useUserStore } from '@/stores/user';

/**
 * 路由守卫：未登录 → 登录页；已登录访问登录页 → 回工具列表；
 * 无权限点 → 不进入（菜单同时不渲染）。
 * 前端拦截只影响体验，接口校验以后端为准。
 */
export function setupRouterGuards(router: Router): void {
  router.beforeEach((to) => {
    const userStore = useUserStore();

    if (!userStore.isLoggedIn && to.meta.public !== true) {
      return { name: 'login', query: { redirect: to.fullPath } };
    }

    if (userStore.isLoggedIn && to.name === 'login') {
      return { name: 'tool-list' };
    }

    if (to.meta.permission && !userStore.has(to.meta.permission)) {
      return { name: 'tool-list' };
    }

    return true;
  });
}
