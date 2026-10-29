/**
 * 测试范围：初始化表单校验（src/utils/initValidation.ts）
 * 测试类型：单元
 * 测试目的：锁死账号与密码规则，避免弱口令或非法账号进入初始化
 * 防回归问题：账号格式未校验、密码长度未校验、两次密码不一致可提交
 * 关键不变量：任一规则不满足即返回对应提示；全部满足返回空串
 * 边界说明：不覆盖接口侧校验（后端必须再次校验）
 * 依赖与限制：纯函数测试，无 DOM
 */
import { describe, expect, it } from 'vitest';
import { PASSWORD_MAX, PASSWORD_MIN, validateInitForm } from '@/utils/initValidation';

function values(patch: Partial<Parameters<typeof validateInitForm>[0]> = {}) {
  return {
    adminUsername: 'admin001',
    adminRealName: '管理员',
    adminPassword: '123456',
    confirmPassword: '123456',
    ...patch,
  };
}

describe('初始化表单校验', () => {
  it('全部满足时通过', () => {
    expect(validateInitForm(values())).toBe('');
  });

  it('账号：必填、长度 4–32、仅字母数字下划线', () => {
    expect(validateInitForm(values({ adminUsername: '' }))).toBe('请输入管理员账号');
    expect(validateInitForm(values({ adminUsername: '   ' }))).toBe('请输入管理员账号');
    expect(validateInitForm(values({ adminUsername: 'ab1' }))).toContain('4–32');
    expect(validateInitForm(values({ adminUsername: 'a'.repeat(33) }))).toContain('4–32');
    expect(validateInitForm(values({ adminUsername: 'admin-001' }))).toContain('4–32');
    expect(validateInitForm(values({ adminUsername: 'admin_001' }))).toBe('');
  });

  it('姓名必填', () => {
    expect(validateInitForm(values({ adminRealName: '  ' }))).toBe('请输入管理员姓名');
  });

  it('密码：必填、长度范围内、两次一致', () => {
    expect(validateInitForm(values({ adminPassword: '' }))).toBe('请输入密码');
    expect(validateInitForm(values({ adminPassword: '1'.repeat(PASSWORD_MIN - 1), confirmPassword: '1'.repeat(PASSWORD_MIN - 1) }))).toContain(
      `${PASSWORD_MIN}–${PASSWORD_MAX}`,
    );
    expect(validateInitForm(values({ adminPassword: '1'.repeat(PASSWORD_MAX + 1), confirmPassword: '1'.repeat(PASSWORD_MAX + 1) }))).toContain(
      `${PASSWORD_MIN}–${PASSWORD_MAX}`,
    );
    expect(validateInitForm(values({ confirmPassword: '654321' }))).toBe('两次输入的密码不一致');
  });
});
