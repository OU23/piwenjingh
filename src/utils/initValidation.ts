import { PASSWORD_MAX, PASSWORD_MIN, USERNAME_PATTERN, passwordRuleHint, usernameRuleHint } from './accountRules';

/**
 * 系统初始化的表单校验（纯函数，便于单测）
 * 规则见 docs/plan/P2-前端核心页面.md T1；账号与密码规则复用 `accountRules`（单一来源）。
 */

export interface InitFormValues {
  adminUsername: string;
  adminPassword: string;
  confirmPassword: string;
  adminRealName: string;
}

export { PASSWORD_MAX, PASSWORD_MIN, USERNAME_PATTERN };

/** 返回第一条不满足的提示；全部通过返回空字符串 */
export function validateInitForm(values: InitFormValues): string {
  const username = values.adminUsername.trim();

  if (!username) {
    return '请输入管理员账号';
  }
  if (!USERNAME_PATTERN.test(username)) {
    return usernameRuleHint();
  }
  if (!values.adminRealName.trim()) {
    return '请输入管理员姓名';
  }
  if (!values.adminPassword) {
    return '请输入密码';
  }
  if (values.adminPassword.length < PASSWORD_MIN || values.adminPassword.length > PASSWORD_MAX) {
    return passwordRuleHint();
  }
  if (values.adminPassword !== values.confirmPassword) {
    return '两次输入的密码不一致';
  }
  return '';
}
