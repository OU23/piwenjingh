/**
 * 测试范围：全局设置的取值与校验（src/utils/settingValidation.ts）
 * 测试类型：单元
 * 测试目的：锁死"只提交变更项"与"路径必须是绝对路径"（P4 验收 A6）
 * 防回归问题：把未改动的项也提交、相对路径放行、上限越界不拦、确认文案丢了迁移提示
 * 关键不变量：
 *   - buildSettingSaveDTO 只包含发生变化的字段；无变化时返回空对象
 *   - 存储根路径必须是绝对路径（Linux `/` 开头，Windows 盘符开头）
 *   - 保存确认文案必须含"不会自动迁移"
 * 边界说明：路径存在性与可写性由后端校验（契约 §十）
 * 依赖与限制：纯函数测试，无 DOM
 */
import { describe, expect, it } from 'vitest';
import type { SettingVO } from '@/api/setting';
import {
  BYTES_PER_GB,
  FILE_SIZE_LIMIT_GB,
  buildSettingSaveDTO,
  hasSettingChanges,
  isAbsolutePath,
  settingConfirmMessage,
  toSettingFormValues,
  validateSettingForm,
} from '@/utils/settingValidation';

const CURRENT: SettingVO = {
  storageRoot: '/data/toolbox/files',
  maxFileSize: 10 * BYTES_PER_GB,
};

describe('设置表单取值', () => {
  it('字节换算为 GB（保留一位小数）', () => {
    expect(toSettingFormValues(CURRENT).maxFileSizeGb).toBe(10);
    expect(toSettingFormValues({ storageRoot: '/a', maxFileSize: 1.5 * BYTES_PER_GB }).maxFileSizeGb).toBe(1.5);
  });

  it('绝对路径判定', () => {
    expect(isAbsolutePath('/data/toolbox/files')).toBe(true);
    expect(isAbsolutePath('D:\\toolbox\\files')).toBe(true);
    expect(isAbsolutePath('D:/toolbox/files')).toBe(true);
    expect(isAbsolutePath('data/toolbox')).toBe(false);
    expect(isAbsolutePath('toolbox/files')).toBe(false);
  });
});

describe('设置表单校验', () => {
  it('通过', () => {
    expect(validateSettingForm({ storageRoot: '/data/toolbox/files', maxFileSizeGb: 10 })).toBe('');
  });

  it('路径必填且必须是绝对路径', () => {
    expect(validateSettingForm({ storageRoot: '', maxFileSizeGb: 10 })).toBe('请输入存储根路径');
    expect(validateSettingForm({ storageRoot: 'data/toolbox', maxFileSizeGb: 10 })).toContain('绝对路径');
  });

  it('单文件上限越界被拦', () => {
    expect(validateSettingForm({ storageRoot: '/a', maxFileSizeGb: FILE_SIZE_LIMIT_GB.min - 1 })).toContain('不能小于');
    expect(validateSettingForm({ storageRoot: '/a', maxFileSizeGb: FILE_SIZE_LIMIT_GB.max + 1 })).toContain('不能大于');
  });
});

describe('只提交变更项', () => {
  it('无改动时返回空对象，且判定为无变化', () => {
    const dto = buildSettingSaveDTO(CURRENT, { storageRoot: '/data/toolbox/files', maxFileSizeGb: 10 });

    expect(dto).toEqual({});
    expect(hasSettingChanges(dto)).toBe(false);
  });

  it('只改路径时仅提交 storageRoot', () => {
    const dto = buildSettingSaveDTO(CURRENT, { storageRoot: '/data/new/files', maxFileSizeGb: 10 });

    expect(dto).toEqual({ storageRoot: '/data/new/files' });
    expect(dto.maxFileSize).toBeUndefined();
    expect(hasSettingChanges(dto)).toBe(true);
  });

  it('只改上限时仅提交 maxFileSize（字节）', () => {
    const dto = buildSettingSaveDTO(CURRENT, { storageRoot: '/data/toolbox/files', maxFileSizeGb: 20 });

    expect(dto).toEqual({ maxFileSize: 20 * BYTES_PER_GB });
    expect(dto.storageRoot).toBeUndefined();
  });

  it('路径首尾空格不影响比较', () => {
    const dto = buildSettingSaveDTO(CURRENT, { storageRoot: '  /data/toolbox/files  ', maxFileSizeGb: 10 });

    expect(dto).toEqual({});
  });
});

describe('保存确认文案', () => {
  it('改路径时必须提示不会自动迁移', () => {
    expect(settingConfirmMessage(true)).toContain('不会自动迁移');
  });

  it('只改上限时说明生效范围', () => {
    expect(settingConfirmMessage(false)).not.toContain('不会自动迁移');
    expect(settingConfirmMessage(false)).toContain('上传');
  });
});
