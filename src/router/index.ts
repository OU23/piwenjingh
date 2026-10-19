import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';
import { PERMISSION } from '@/constants/permissions';

declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录访问 */
    public?: boolean;
    /** 需要的权限点；不填 = 登录即可 */
    permission?: string;
  }
}

/**
 * 路由表（页面与路由对应关系见 docs/内部-前端蓝图对照.md §1）
 * 页面本体在 P2–P4 实现，本阶段只保证骨架、守卫与菜单可达。
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/init',
    name: 'init',
    component: () => import('@/views/login/InitView.vue'),
    meta: { public: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: AppLayout,
    redirect: { name: 'tool-list' },
    children: [
      {
        path: 'tools',
        name: 'tool-list',
        component: () => import('@/views/tool/ToolListView.vue'),
      },
      {
        path: 'tools/:id',
        name: 'tool-detail',
        component: () => import('@/views/tool/ToolDetailView.vue'),
      },
      {
        path: 'upload',
        name: 'tool-upload',
        component: () => import('@/views/tool/UploadView.vue'),
        meta: { permission: PERMISSION.TOOL_UPLOAD },
      },
      {
        path: 'admin/deleted',
        name: 'tool-deleted',
        component: () => import('@/views/admin/DeletedView.vue'),
        // v1.21：登录即可进入回收站（无 tool:delete 时后端只返回本人数据）
      },
      {
        path: 'admin/user',
        name: 'admin-user',
        component: () => import('@/views/admin/UserView.vue'),
        meta: { permission: PERMISSION.USER_MANAGE },
      },
      {
        path: 'admin/dept',
        name: 'admin-dept',
        component: () => import('@/views/admin/DeptView.vue'),
        meta: { permission: PERMISSION.DEPT_MANAGE },
      },
      {
        path: 'admin/tag',
        name: 'admin-tag',
        component: () => import('@/views/admin/TagView.vue'),
        meta: { permission: PERMISSION.TAG_MANAGE },
      },
      {
        path: 'admin/setting',
        name: 'admin-setting',
        component: () => import('@/views/admin/SettingView.vue'),
        meta: { permission: PERMISSION.SYS_SETTING },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

export default router;
