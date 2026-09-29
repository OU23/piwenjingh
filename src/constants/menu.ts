import { PERMISSION, type PermissionCode } from './permissions';

export interface MenuItem {
  /** 与路由 name 一致 */
  name: string;
  title: string;
  /** 需要的权限点；不填 = 登录即可见 */
  permission?: PermissionCode;
}

export interface MenuGroup {
  title: string;
  items: MenuItem[];
}

/**
 * 左侧导航（与 docs/内部-前端蓝图对照.md §1 一致）
 * 无权限的菜单项不渲染；前端显隐只影响体验，后端校验为最终依据。
 */
export const MENU: MenuGroup[] = [
  {
    title: '工具仓库',
    items: [
      { name: 'tool-list', title: '工具列表' },
      { name: 'tool-upload', title: '上传工具', permission: PERMISSION.TOOL_UPLOAD },
      { name: 'tool-deleted', title: '已删除工具', permission: PERMISSION.TOOL_DELETE },
    ],
  },
  {
    title: '系统管理',
    items: [
      { name: 'admin-user', title: '用户管理', permission: PERMISSION.USER_MANAGE },
      { name: 'admin-dept', title: '部门管理', permission: PERMISSION.DEPT_MANAGE },
      { name: 'admin-tag', title: '标签管理', permission: PERMISSION.TAG_MANAGE },
      { name: 'admin-setting', title: '全局设置', permission: PERMISSION.SYS_SETTING },
    ],
  },
];
