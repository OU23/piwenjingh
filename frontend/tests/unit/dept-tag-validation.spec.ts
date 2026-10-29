/**
 * 测试范围：部门与标签表单校验（src/utils/deptValidation.ts）
 * 测试类型：单元
 * 测试目的：锁死名称必填与长度上限（P4 验收 A4 / A5 的校验部分）
 * 防回归问题：空名称放行、超长不拦
 * 关键不变量：任一规则不满足返回对应提示；通过返回空串
 * 边界说明：重名由后端判定（code 1005）
 * 依赖与限制：纯函数测试，无 DOM
 */
import { describe, expect, it } from 'vitest';
import {
  DEPT_NAME_MAX,
  DEPT_REMARK_MAX,
  TAG_NAME_MAX,
  validateDeptForm,
  validateTagName,
} from '@/utils/deptValidation';

describe('部门表单校验', () => {
  it('通过', () => {
    expect(validateDeptForm({ name: '研发部', remark: '负责平台研发' })).toBe('');
    expect(validateDeptForm({ name: '研发部', remark: '' })).toBe('');
  });

  it('名称必填且不超过 50 字符', () => {
    expect(validateDeptForm({ name: '', remark: '' })).toBe('请输入部门名称');
    expect(validateDeptForm({ name: '  ', remark: '' })).toBe('请输入部门名称');
    expect(validateDeptForm({ name: '部'.repeat(DEPT_NAME_MAX + 1), remark: '' })).toContain('50');
  });

  it('备注不超过 200 字符', () => {
    expect(validateDeptForm({ name: '研发部', remark: '备'.repeat(DEPT_REMARK_MAX + 1) })).toContain('200');
  });
});

describe('标签名称校验', () => {
  it('通过', () => {
    expect(validateTagName('数据处理')).toBe('');
  });

  it('必填且不超过 20 字符', () => {
    expect(validateTagName('')).toBe('请输入标签名称');
    expect(validateTagName('   ')).toBe('请输入标签名称');
    expect(validateTagName('标'.repeat(TAG_NAME_MAX + 1))).toContain('20');
  });
});
