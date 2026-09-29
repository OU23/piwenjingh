/**
 * 测试范围：工具列表查询参数拼装（src/utils/toolQuery.ts）
 * 测试类型：单元
 * 测试目的：锁死"筛选条件 → 请求参数"的口径（契约 §9.2），避免联调期参数对不上
 * 防回归问题：多选值未按逗号拼接、格式未转小写、空条件仍下发、分页超出上限未收敛
 * 关键不变量：
 *   - tagIds / fileExt 多值用英文逗号分隔（fileExt 一律小写）
 *   - 空字符串与空数组条件不下发
 *   - pageSize 超过 100 时收敛为 100；非法值回落默认 20
 *   - hasActiveFilters 正确区分"无数据"与"筛选无结果"两种空态
 * 边界说明：不覆盖真实接口调用与分页渲染
 * 依赖与限制：纯函数测试，无 DOM
 */
import { describe, expect, it } from 'vitest';
import { PAGE_SIZE_DEFAULT, PAGE_SIZE_MAX, SORT_BY, SORT_ORDER } from '@/constants/enums';
import {
  buildToolListQuery,
  createDefaultFilters,
  hasActiveFilters,
  normalizePageSize,
  type ToolFilters,
} from '@/utils/toolQuery';

function filtersOf(patch: Partial<ToolFilters> = {}): ToolFilters {
  return { ...createDefaultFilters(), ...patch };
}

describe('工具列表查询参数拼装', () => {
  it('默认条件：只带分页与排序，不带空筛选条件', () => {
    const query = buildToolListQuery(createDefaultFilters(), 1, PAGE_SIZE_DEFAULT);

    expect(query).toEqual({
      pageNum: 1,
      pageSize: 20,
      sortBy: SORT_BY.CREATE_TIME,
      sortOrder: SORT_ORDER.DESC,
    });
    expect(query).not.toHaveProperty('keyword');
    expect(query).not.toHaveProperty('tagIds');
    expect(query).not.toHaveProperty('fileExt');
    expect(query).not.toHaveProperty('scope');
  });

  it('标签多选按逗号拼接，关键词去除首尾空格', () => {
    const query = buildToolListQuery(filtersOf({ keyword: '  压缩  ', tagIds: ['1', '3', '8'] }), 2, 20);

    expect(query.keyword).toBe('压缩');
    expect(query.tagIds).toBe('1,3,8');
  });

  it('格式多值转小写并按逗号拼接（含去重前的清理）', () => {
    const query = buildToolListQuery(filtersOf({ fileExts: ['ZIP', ' Py ', ''] }), 1, 20);

    expect(query.fileExt).toBe('zip,py');
  });

  it('可见范围为空字符串时不参与筛选', () => {
    expect(buildToolListQuery(filtersOf({ scope: '' }), 1, 20).scope).toBeUndefined();
    expect(buildToolListQuery(filtersOf({ scope: 'PUBLIC' }), 1, 20).scope).toBe('PUBLIC');
  });

  it('pageSize 超过上限收敛为 100，非法值回落默认值', () => {
    expect(normalizePageSize(500)).toBe(PAGE_SIZE_MAX);
    expect(normalizePageSize(0)).toBe(PAGE_SIZE_DEFAULT);
    expect(normalizePageSize(-3)).toBe(PAGE_SIZE_DEFAULT);
    expect(normalizePageSize(Number.NaN)).toBe(PAGE_SIZE_DEFAULT);

    expect(buildToolListQuery(createDefaultFilters(), 1, 999).pageSize).toBe(PAGE_SIZE_MAX);
  });

  it('页码非法时收敛为第 1 页', () => {
    expect(buildToolListQuery(createDefaultFilters(), 0, 20).pageNum).toBe(1);
    expect(buildToolListQuery(createDefaultFilters(), -2, 20).pageNum).toBe(1);
    expect(buildToolListQuery(createDefaultFilters()).pageNum).toBe(1);
  });

  it('筛选状态判定：用于区分两种空态', () => {
    expect(hasActiveFilters(createDefaultFilters())).toBe(false);
    expect(hasActiveFilters(filtersOf({ keyword: '   ' }))).toBe(false);
    expect(hasActiveFilters(filtersOf({ keyword: 'x' }))).toBe(true);
    expect(hasActiveFilters(filtersOf({ tagIds: ['1'] }))).toBe(true);
    expect(hasActiveFilters(filtersOf({ fileExts: ['zip'] }))).toBe(true);
    expect(hasActiveFilters(filtersOf({ scope: 'DEPT' }))).toBe(true);
  });

  it('排序条件可切换', () => {
    const query = buildToolListQuery(filtersOf({ sortBy: SORT_BY.DOWNLOAD_COUNT, sortOrder: SORT_ORDER.ASC }), 1, 20);

    expect(query.sortBy).toBe('downloadCount');
    expect(query.sortOrder).toBe('asc');
  });
});
