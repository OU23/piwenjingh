/**
 * 账号与密码规则的**单一来源**
 * 同时被「系统初始化建首个管理员」与「管理员新建账号」使用，避免同一规则写两份而漂移。
 * 规则来源：docs/接口契约.md §六（UserSaveDTO）/ §十一
 *
 * 说明：这里只放**规则本身**（正则与长度），提示文案由各表单按场景给出。
 */

/** 账号：4–32 位字母、数字、下划线（全局唯一，登录名） */
export const USERNAME_PATTERN = /^[A-Za-z0-9_]{4,32}$/;
export const USERNAME_MIN = 4;
export const USERNAME_MAX = 32;

/** 密码：6–32 位 */
export const PASSWORD_MIN = 6;
export const PASSWORD_MAX = 32;

/** 账号格式提示（各表单共用同一措辞） */
export function usernameRuleHint(): string {
  return `账号需为 ${USERNAME_MIN}–${USERNAME_MAX} 位字母、数字或下划线`;
}

export function passwordRuleHint(): string {
  return `密码长度需为 ${PASSWORD_MIN}–${PASSWORD_MAX} 位`;
}
