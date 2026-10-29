import { PASSWORD_MAX, PASSWORD_MIN, USERNAME_PATTERN, passwordRuleHint, usernameRuleHint } from './accountRules';
import type { ID } from '@/api/types';

/**
 * 用户管理表单校验（纯函数，便于单测）
 * 规则与字段来源：docs/接口契约.md §六（UserSaveDTO / UserUpdateDTO）
 */

/** 姓名长度上限（契约：≤50 字符） */
export const REAL_NAME_MAX = 50;

export interface UserCreateValues {
  username: string;
  realName: string;
  password: string;
  deptId: ID | '';
}

export interface UserUpdateValues {
  realName: string;
  deptId: ID | '';
}

/** 新建账号：账号 / 姓名 / 初始密码 / 归属部门（契约中 deptId 为必填） */
export function validateUserCreate(values: UserCreateValues): string {
  const username = values.username.trim();

  if (!username) {
    return '请输入账号';
  }
  if (!USERNAME_PATTERN.test(username)) {
    return usernameRuleHint();
  }
  if (!values.realName.trim()) {
    return '请输入姓名';
  }
  if (values.realName.trim().length > REAL_NAME_MAX) {
    return `姓名不能超过 ${REAL_NAME_MAX} 个字符`;
  }
  if (!values.password) {
    return '请输入初始密码';
  }
  if (values.password.length < PASSWORD_MIN || values.password.length > PASSWORD_MAX) {
    return passwordRuleHint();
  }
  if (!values.deptId) {
    return '请选择归属部门';
  }
  return '';
}

/** 编辑账号：仅姓名与归属部门可改（账号不可改） */
export function validateUserUpdate(values: UserUpdateValues): string {
  if (!values.realName.trim()) {
    return '请输入姓名';
  }
  if (values.realName.trim().length > REAL_NAME_MAX) {
    return `姓名不能超过 ${REAL_NAME_MAX} 个字符`;
  }
  if (!values.deptId) {
    return '请选择归属部门';
  }
  return '';
}
