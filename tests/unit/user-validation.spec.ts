/**
 * 测试范围：用户管理表单校验（src/utils/userValidation.ts）
 * 测试类型：单元
 * 测试目的：锁死账号 / 姓名 / 初始密码 / 归属部门的规则（P4 验收 A3）
 * 防回归问题：账号格式不校验、密码长度不校验、归属部门可空提交
 * 关键不变量：任一规则不满足返回对应提示；通过返回空串
 * 边界说明：不覆盖后端唯一性与部门存在性校验
 * 依赖与限制：纯函数测试，无 DOM
 */
import { describe, expect, it } from 'vitest';
import { REAL_NAME_MAX, validateUserCreate, validateUserUpdate } from '@/utils/userValidation';

function values(patch: Partial<Parameters<typeof validateUserCreate>[0]> = {}) {
  return {
    username: 'zhangsan',
    realName: '张三',
    password: '123456',
    deptId: '1',
    ...patch,
  };
}

describe('新建账号校验', () => {
  it('全部满足时通过', () => {
    expect(validateUserCreate(values())).toBe('');
  });

  it('账号：必填、4–32 位字母数字下划线', () => {
    expect(validateUserCreate(values({ username: '' }))).toBe('请输入账号');
    expect(validateUserCreate(values({ username: '  ' }))).toBe('请输入账号');
    expect(validateUserCreate(values({ username: 'ab1' }))).toContain('4–32');
    expect(validateUserCreate(values({ username: 'a'.repeat(33) }))).toContain('4–32');
    expect(validateUserCreate(values({ username: 'zhang-san' }))).toContain('4–32');
    expect(validateUserCreate(values({ username: 'zhang_san' }))).toBe('');
  });

  it('姓名：必填且不超过 50 字符', () => {
    expect(validateUserCreate(values({ realName: ' ' }))).toBe('请输入姓名');
    expect(validateUserCreate(values({ realName: '张'.repeat(REAL_NAME_MAX + 1) }))).toContain('50');
  });

  it('初始密码：必填且 6–32 位', () => {
    expect(validateUserCreate(values({ password: '' }))).toBe('请输入初始密码');
    expect(validateUserCreate(values({ password: '12345' }))).toContain('6–32');
    expect(validateUserCreate(values({ password: '1'.repeat(33) }))).toContain('6–32');
  });

  it('归属部门必选', () => {
    expect(validateUserCreate(values({ deptId: '' }))).toBe('请选择归属部门');
  });
});

describe('编辑账号校验（仅姓名与部门）', () => {
  it('姓名与部门必填', () => {
    expect(validateUserUpdate({ realName: '', deptId: '1' })).toBe('请输入姓名');
    expect(validateUserUpdate({ realName: '张三', deptId: '' })).toBe('请选择归属部门');
    expect(validateUserUpdate({ realName: '张三', deptId: '1' })).toBe('');
  });
});
