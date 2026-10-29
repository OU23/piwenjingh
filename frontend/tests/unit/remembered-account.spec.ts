/**
 * 测试范围：「记住密码」本地存储（src/utils/rememberedAccount.ts）
 * 测试类型：单元（jsdom）
 * 测试目的：锁死「保存 → 读回」「清除」「损坏数据回落」与「不明文直存」
 * 防回归问题：读回丢字段、损坏数据抛异常、把密码明文写进本地存储
 * 边界说明：混淆≠加密，仅验证"非明文直存"，不宣称安全性
 */
import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearRememberedAccount,
  readRememberedAccount,
  REMEMBERED_ACCOUNT_KEY,
  saveRememberedAccount,
} from '@/utils/rememberedAccount';

describe('记住密码存储', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('未保存时返回 null', () => {
    expect(readRememberedAccount()).toBeNull();
  });

  it('保存后能读回账号与密码（含中文/特殊字符）', () => {
    saveRememberedAccount('admin', '密码@123');
    expect(readRememberedAccount()).toEqual({ username: 'admin', password: '密码@123' });
  });

  it('存储内容不是明文直存（做了轻量混淆）', () => {
    saveRememberedAccount('admin', 'admin123');
    const raw = localStorage.getItem(REMEMBERED_ACCOUNT_KEY) ?? '';
    expect(raw).not.toContain('admin123');
    expect(raw.length).toBeGreaterThan(0);
  });

  it('清除后返回 null', () => {
    saveRememberedAccount('admin', 'admin123');
    clearRememberedAccount();
    expect(readRememberedAccount()).toBeNull();
  });

  it('损坏数据返回 null（不抛异常）', () => {
    localStorage.setItem(REMEMBERED_ACCOUNT_KEY, '{not-json');
    expect(readRememberedAccount()).toBeNull();
  });
});
