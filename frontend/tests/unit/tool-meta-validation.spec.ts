/**
 * 测试范围：工具元数据表单校验（src/utils/toolMetaValidation.ts）
 * 测试类型：单元
 * 测试目的：锁死必填、长度上限、可见范围必选（P3 验收 A8）
 * 防回归问题：必填漏校验、超长不拦、可见范围可空提交、追加版本模式误要求填简介
 * 关键不变量：任一规则不满足返回对应提示；通过返回空串
 * 边界说明：不覆盖后端再次校验
 * 依赖与限制：纯函数测试，无 DOM
 */
import { describe, expect, it } from 'vitest';
import {
  TOOL_META_LIMITS,
  createEmptyToolMeta,
  fileExtFromName,
  validateToolMeta,
  type ToolMetaValues,
} from '@/utils/toolMetaValidation';

function values(patch: Partial<ToolMetaValues> = {}): ToolMetaValues {
  return {
    ...createEmptyToolMeta(),
    name: '数据清洗小工具',
    version: 'v1.0',
    intro: '批量清洗 CSV',
    fileExt: 'zip',
    scope: 'DEPT',
    ...patch,
  };
}

describe('元数据校验（新建 / 编辑）', () => {
  it('全部满足时通过', () => {
    expect(validateToolMeta('create', values())).toBe('');
    expect(validateToolMeta('edit', values())).toBe('');
  });

  it('必填项：名称 / 版本 / 简介 / 格式 / 可见范围', () => {
    expect(validateToolMeta('create', values({ name: '  ' }))).toBe('请输入工具名称');
    expect(validateToolMeta('create', values({ version: '' }))).toBe('请输入版本号');
    expect(validateToolMeta('create', values({ intro: ' ' }))).toBe('请输入简介');
    expect(validateToolMeta('create', values({ fileExt: '' }))).toBe('无法从文件名识别文件格式，请返回重新选择带扩展名的文件');
    expect(validateToolMeta('create', values({ scope: '' }))).toBe('请选择可见范围');
  });

  it('长度上限与契约一致', () => {
    expect(validateToolMeta('create', values({ name: 'a'.repeat(TOOL_META_LIMITS.name + 1) }))).toContain('工具名称');
    expect(validateToolMeta('create', values({ version: 'a'.repeat(TOOL_META_LIMITS.version + 1) }))).toContain('版本号');
    expect(validateToolMeta('create', values({ intro: 'a'.repeat(TOOL_META_LIMITS.intro + 1) }))).toContain('简介');
    expect(validateToolMeta('create', values({ dependency: 'a'.repeat(TOOL_META_LIMITS.dependency + 1) }))).toContain('依赖说明');
    expect(validateToolMeta('create', values({ readme: 'a'.repeat(TOOL_META_LIMITS.readme + 1) }))).toContain('使用说明');
  });
});

describe('元数据校验（追加版本）', () => {
  it('只要求版本号', () => {
    expect(validateToolMeta('version', values())).toBe('');
    expect(validateToolMeta('version', values({ version: '' }))).toBe('请输入版本号');
    expect(validateToolMeta('version', values({ version: 'a'.repeat(TOOL_META_LIMITS.version + 1) }))).toContain('版本号');
  });

  it('不因缺少名称 / 简介而报错（这些属工具级信息）', () => {
    expect(validateToolMeta('version', values({ name: '', intro: '', fileExt: '', scope: '' }))).toBe('');
  });
});

describe('文件格式推导', () => {
  it('取最后一个扩展名并转小写', () => {
    expect(fileExtFromName('demo.zip')).toBe('zip');
    expect(fileExtFromName('package.tar.gz')).toBe('gz');
    expect(fileExtFromName('tool-v1.2.ZIP')).toBe('zip');
  });

  it('无扩展名或非法输入返回空串', () => {
    expect(fileExtFromName('README')).toBe('');
    expect(fileExtFromName('')).toBe('');
    expect(fileExtFromName('  ')).toBe('');
  });
});
