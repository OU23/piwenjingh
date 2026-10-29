/**
 * 测试范围：工具列表的「类型筛选」参数拼装（src/utils/toolQuery.ts）
 * 测试类型：单元（纯函数）
 * 测试目的：锁死 M-25 引入的 `toolType` 口径 —— 默认 ALL 不下发、FILE/LINK 下发、参与空态判定
 * 防回归问题：ALL 也把参数发给后端（老后端不认识但语义脏）、类型筛选不触发"筛选无结果"空态
 */
import { describe, expect, it } from 'vitest';
import { buildToolListQuery, createDefaultFilters, hasActiveFilters } from '@/utils/toolQuery';

describe('工具类型筛选（M-25）', () => {
  it('默认 ALL：查询参数里不出现 toolType', () => {
    const filters = createDefaultFilters();

    expect(filters.toolType).toBe('ALL');

    const query = buildToolListQuery(filters, 1, 20);
    expect('toolType' in query).toBe(false);
    expect(query.toolType).toBeUndefined();
  });

  it('FILE / LINK：显式下发对应值', () => {
    expect(buildToolListQuery({ ...createDefaultFilters(), toolType: 'FILE' }).toolType).toBe('FILE');
    expect(buildToolListQuery({ ...createDefaultFilters(), toolType: 'LINK' }).toolType).toBe('LINK');
  });

  it('空串视同 ALL：不下发', () => {
    const query = buildToolListQuery({ ...createDefaultFilters(), toolType: '' });
    expect(query.toolType).toBeUndefined();
  });

  it('参与"是否在筛选"判定：ALL 不算筛选，FILE/LINK 算', () => {
    expect(hasActiveFilters(createDefaultFilters())).toBe(false);
    expect(hasActiveFilters({ ...createDefaultFilters(), toolType: 'ALL' })).toBe(false);
    expect(hasActiveFilters({ ...createDefaultFilters(), toolType: 'LINK' })).toBe(true);
  });
});
