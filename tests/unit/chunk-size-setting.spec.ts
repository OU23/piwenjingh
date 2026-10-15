/**
 * 测试范围：全局设置中的「分片大小」（src/utils/settingValidation.ts）
 * 测试类型：单元
 * 测试目的：锁死 v1.20 新增项 `chunkSize` 的换算、区间校验与"只提交变更项"（M-23 验收 A16 / 方案 §6.4）
 * 防回归问题：MB↔字节换算错误、越界不拦、无改动也提交（多写一次库）
 * 关键不变量：
 *   - 界面 MB ↔ 契约字节双向换算
 *   - 区间 5 ~ 100 MB；越界给出提示
 *   - 未提供该项时视为"不修改"，不进入 DTO
 * 边界说明：后端才是权威（越界 → 1003），此处只做即时提示
 */
import { describe, expect, it } from 'vitest';
import type { SettingVO } from '@/api/setting';
import {
  BYTES_PER_MB,
  CHUNK_SIZE_MB,
  buildSettingSaveDTO,
  hasSettingChanges,
  toSettingFormValues,
  validateSettingForm,
} from '@/utils/settingValidation';

const CURRENT: SettingVO = {
  storageRoot: '/data/toolbox/files',
  maxFileSize: 10 * 1024 ** 3,
  chunkSize: 20 * BYTES_PER_MB,
};

describe('分片大小：取值换算', () => {
  it('字节 → MB', () => {
    expect(toSettingFormValues(CURRENT).chunkSizeMb).toBe(20);
    expect(toSettingFormValues({ ...CURRENT, chunkSize: 50 * BYTES_PER_MB }).chunkSizeMb).toBe(50);
  });

  it('区间常量与后端一致（5 ~ 100 MB）', () => {
    expect(CHUNK_SIZE_MB).toEqual({ min: 5, max: 100 });
  });
});

describe('分片大小：校验', () => {
  it('区间内通过', () => {
    expect(validateSettingForm({ storageRoot: '/a', maxFileSizeGb: 10, chunkSizeMb: 5 })).toBe('');
    expect(validateSettingForm({ storageRoot: '/a', maxFileSizeGb: 10, chunkSizeMb: 100 })).toBe('');
  });

  it('越界被拦', () => {
    expect(validateSettingForm({ storageRoot: '/a', maxFileSizeGb: 10, chunkSizeMb: 4 })).toContain('不能小于');
    expect(validateSettingForm({ storageRoot: '/a', maxFileSizeGb: 10, chunkSizeMb: 101 })).toContain('不能大于');
  });

  it('未提供该项时不参与校验（视为不修改）', () => {
    expect(validateSettingForm({ storageRoot: '/a', maxFileSizeGb: 10 })).toBe('');
  });
});

describe('分片大小：只提交变更项', () => {
  it('改分片大小 → 提交 chunkSize（字节）', () => {
    const dto = buildSettingSaveDTO(CURRENT, { storageRoot: '/data/toolbox/files', maxFileSizeGb: 10, chunkSizeMb: 50 });

    expect(dto).toEqual({ chunkSize: 50 * BYTES_PER_MB });
    expect(hasSettingChanges(dto)).toBe(true);
  });

  it('与当前值一致 → 不下发', () => {
    const dto = buildSettingSaveDTO(CURRENT, { storageRoot: '/data/toolbox/files', maxFileSizeGb: 10, chunkSizeMb: 20 });

    expect(dto).toEqual({});
    expect(hasSettingChanges(dto)).toBe(false);
  });

  it('未提供该项 → 不下发（与 DTO"选填"语义一致）', () => {
    const dto = buildSettingSaveDTO(CURRENT, { storageRoot: '/data/new/files', maxFileSizeGb: 10 });

    expect(dto).toEqual({ storageRoot: '/data/new/files' });
    expect(dto.chunkSize).toBeUndefined();
  });
});
