/**
 * 测试范围：选取文件的预校验（src/utils/fileValidation.ts）
 * 测试类型：单元
 * 测试目的：只拦"确定无法上传"的文件，同时**不误伤正常文件名**（P3 验收 C1 / C2 / C3）
 * 防回归问题：空文件放行、超上限不拦、把中文名 / 特殊字符 / 长文件名当成非法（误伤用户）
 * 关键不变量：
 *   - 0 字节 → 拒绝
 *   - 超过上限 → 拒绝，提示含当前上限
 *   - 中文、空格、括号、连字符、较长文件名 → 通过（不得报错）
 * 边界说明：上限由全局设置提供，本函数只做预校验；后端必须再次校验
 * 依赖与限制：纯函数测试，无 DOM
 */
import { describe, expect, it } from 'vitest';
import { validatePickedFile } from '@/utils/fileValidation';

const MB = 1024 * 1024;

function fileOf(size: number, name = 'demo.zip'): File {
  return new File([new Uint8Array(size)], name);
}

describe('选取文件预校验', () => {
  it('正常文件通过', () => {
    expect(validatePickedFile(fileOf(1024), 10 * MB)).toBe('');
  });

  it('空文件被拒绝（验收 C1）', () => {
    expect(validatePickedFile(fileOf(0), 10 * MB)).toBe('文件为空，请重新选择');
  });

  it('超过上限被拒绝，且提示当前上限（验收 C3）', () => {
    const message = validatePickedFile(fileOf(20 * MB), 10 * MB);

    expect(message).toContain('超过上限');
    expect(message).toContain('10.0 MB');
  });

  it('上限为 0（未读取到）时只判空文件，不误拦', () => {
    expect(validatePickedFile(fileOf(500 * MB), 0)).toBe('');
  });

  it('中文名 / 特殊字符 / 较长文件名都不得报错（验收 C2）', () => {
    const names = [
      '数据清洗工具-v1.2.zip',
      'a b(c)[d]{e}#f&g.zip',
      '工具 名称 带 空格 与 中文.tar.gz',
      `${'长'.repeat(120)}.zip`,
      'tool_v1.0.0-beta.1+build.2026.zip',
    ];

    names.forEach((name) => {
      expect(validatePickedFile(fileOf(1024, name), 10 * MB)).toBe('');
    });
  });
});
