/**
 * 测试范围：上传上下文加载（src/api/uploadContext.ts）——部分依赖失败时的降级与提示
 * 测试类型：单元（纯逻辑，不挂组件）
 * 测试目的：锁死"辅助数据（标签字典 / 单文件上限）加载失败必须可见"的口径
 * 防回归问题：并行加载里某个依赖失败被静默吞掉，页面出现空下拉却不给任何解释
 * 关键不变量：
 *   - 任一依赖失败都不抛错，其余字段照常返回（不阻断上传主流程）
 *   - 失败的依赖必须在对应 error 字段留下原因
 *   - 目标工具不存在时 tool 为 null，且不影响其他字段
 * 边界说明：不验证页面如何展示（组件层另有测试）
 * 依赖与限制：三个接口模块均为桩
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  getSettings: vi.fn(),
  getUploadMode: vi.fn(),
  listTags: vi.fn(),
  getToolDetail: vi.fn(),
}));

vi.mock('@/api/setting', () => ({ getSettings: mocks.getSettings }));
// v1.20 新增依赖（M-23）：上传页需先查上传模式 —— 夹具补齐，未改动任何断言
vi.mock('@/api/uploadMode', () => ({ getUploadMode: mocks.getUploadMode }));
vi.mock('@/api/tag', () => ({ listTags: mocks.listTags }));
vi.mock('@/api/tool', () => ({ getToolDetail: mocks.getToolDetail }));

import { loadUploadContext } from '@/api/uploadContext';

const TAG = { id: '1', name: '数据处理', toolCount: 3, createTime: '2026-09-01 10:00:00' };

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getSettings.mockResolvedValue({
    storageRoot: '/data/toolbox/files',
    maxFileSize: 10737418240,
    chunkSize: 20971520,
  });
  mocks.getUploadMode.mockResolvedValue({
    mode: 'server',
    partSize: 20971520,
    partSizeMin: 5242880,
    partSizeMax: 104857600,
    maxFileSize: 10737418240,
  });
  mocks.listTags.mockResolvedValue([TAG]);
  mocks.getToolDetail.mockResolvedValue(null);
});

describe('上传上下文加载', () => {
  it('全部依赖成功时不产生任何错误提示', async () => {
    const context = await loadUploadContext();

    expect(context.maxFileSize).toBe(10737418240);
    expect(context.maxFileSizeError).toBe('');
    expect(context.tags).toHaveLength(1);
    expect(context.tagsError).toBe('');
    expect(context.tool).toBeNull();
  });

  it('标签字典加载失败：给出失败原因，其余字段不受影响', async () => {
    mocks.listTags.mockRejectedValue(new Error('标签服务暂时不可用'));

    const context = await loadUploadContext();

    expect(context.tags).toEqual([]);
    expect(context.tagsError).toBe('标签服务暂时不可用');
    // 关键：上限仍可用 → 不影响上传主流程
    expect(context.maxFileSize).toBe(10737418240);
    expect(context.maxFileSizeError).toBe('');
  });

  it('单文件上限读取失败：给出失败原因，标签不受影响', async () => {
    mocks.getSettings.mockRejectedValue(new Error('设置读取失败'));

    const context = await loadUploadContext();

    expect(context.maxFileSize).toBe(0);
    expect(context.maxFileSizeError).toBe('设置读取失败');
    expect(context.tags).toHaveLength(1);
  });

  it('两个依赖同时失败：两条提示各自独立，不互相覆盖', async () => {
    mocks.getSettings.mockRejectedValue(new Error('设置读取失败'));
    mocks.listTags.mockRejectedValue(new Error('标签服务暂时不可用'));

    const context = await loadUploadContext();

    expect(context.maxFileSizeError).toBe('设置读取失败');
    expect(context.tagsError).toBe('标签服务暂时不可用');
  });

  it('非 Error 抛出物：退化为可读文案，不出现 undefined', async () => {
    mocks.listTags.mockRejectedValue('boom');

    const context = await loadUploadContext();

    expect(context.tagsError).toBe('无法读取标签字典');
  });

  it('追加版本模式：目标工具信息被映射为表单预填值', async () => {
    mocks.getToolDetail.mockResolvedValue({
      id: '1001',
      name: '数据清洗小工具',
      fileExt: 'zip',
      platform: 'Windows',
      dependency: 'Python 3.11',
      intro: '批量清洗 CSV',
      scope: 'DEPT',
      tags: [{ id: '1', name: '数据处理' }],
    });

    const context = await loadUploadContext('1001');

    expect(context.tool).toEqual({
      id: '1001',
      name: '数据清洗小工具',
      fileExt: 'zip',
      platform: 'Windows',
      dependency: 'Python 3.11',
      intro: '批量清洗 CSV',
      scope: 'DEPT',
      tagIds: ['1'],
    });
  });
});
