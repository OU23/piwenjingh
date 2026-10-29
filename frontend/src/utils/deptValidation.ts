/**
 * 部门与标签的表单校验（纯函数，便于单测）
 * 规则来源：docs/接口契约.md §四（DeptSaveDTO）/ §七（TagSaveDTO）
 */

/** 部门名称上限（契约：≤50 字符，不可重名） */
export const DEPT_NAME_MAX = 50;
/** 部门备注上限（契约：≤200 字符） */
export const DEPT_REMARK_MAX = 200;
/** 标签名称上限（计划 T4：≤20 字，不可重名） */
export const TAG_NAME_MAX = 20;

export interface DeptFormValues {
  name: string;
  remark: string;
}

/** 返回第一条不满足的提示；通过返回空字符串 */
export function validateDeptForm(values: DeptFormValues): string {
  const name = values.name.trim();

  if (!name) {
    return '请输入部门名称';
  }
  if (name.length > DEPT_NAME_MAX) {
    return `部门名称不能超过 ${DEPT_NAME_MAX} 个字符`;
  }
  if (values.remark.trim().length > DEPT_REMARK_MAX) {
    return `备注不能超过 ${DEPT_REMARK_MAX} 个字符`;
  }
  return '';
}

/** 标签名称校验 */
export function validateTagName(name: string): string {
  const value = name.trim();

  if (!value) {
    return '请输入标签名称';
  }
  if (value.length > TAG_NAME_MAX) {
    return `标签名称不能超过 ${TAG_NAME_MAX} 个字符`;
  }
  return '';
}
