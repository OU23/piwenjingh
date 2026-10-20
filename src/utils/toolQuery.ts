import type { ToolListQuery } from '@/api/tool';
import { PAGE_SIZE_DEFAULT, PAGE_SIZE_MAX, SORT_BY, SORT_ORDER } from '@/constants/enums';

/**
 * 工具列表的筛选条件与查询参数拼装（纯函数，便于单测）
 * 参数口径见 docs/接口契约.md §9.2：标签与格式均为英文逗号分隔，格式小写。
 */

export interface ToolFilters {
  /** 关键词：匹配工具名称 + 简介 */
  keyword: string;
  /** 标签 ID，多选取（命中任一即匹配） */
  tagIds: string[];
  /** 文件格式，多选取（发送前统一转小写） */
  fileExts: string[];
  /** 可见范围；空字符串 = 全部 */
  scope: string;
  /** 工具类型（契约 v1.23）：`ALL` / `FILE` / `LINK`；`ALL` 不下发 */
  toolType: string;
  sortBy: string;
  sortOrder: string;
}

/** 文件格式候选（常见的可执行文件、脚本与归档包） */
export const FILE_EXT_OPTIONS: string[] = [
  'exe',
  'msi',
  'dll',
  'zip',
  '7z',
  'rar',
  'gz',
  'tar',
  'py',
  'jar',
  'java',
  'php',
  'sh',
  'bat',
  'ps1',
  'js',
  'apk',
  'iso',
  'pdf',
  'docx',
  'xlsx',
  'txt',
  'json',
  'xml',
];

/** 默认筛选条件：上传时间倒序（见 docs/待确认清单.md Q-02） */
export const DEFAULT_FILTERS: ToolFilters = {
  keyword: '',
  tagIds: [],
  fileExts: [],
  scope: '',
  toolType: 'ALL',
  sortBy: SORT_BY.CREATE_TIME,
  sortOrder: SORT_ORDER.DESC,
};

/** 每次调用返回新的对象，避免筛选条件数组被共享引用 */
export function createDefaultFilters(): ToolFilters {
  return { ...DEFAULT_FILTERS, tagIds: [], fileExts: [] };
}

function normalizePageNum(value?: number): number {
  if (value === undefined || !Number.isFinite(value) || value < 1) {
    return 1;
  }
  return Math.floor(value);
}

/** 每页条数：非法值回落到默认值，超过上限一律收敛（契约 §1.5） */
export function normalizePageSize(value?: number): number {
  if (value === undefined || !Number.isFinite(value) || value < 1) {
    return PAGE_SIZE_DEFAULT;
  }
  return Math.min(Math.floor(value), PAGE_SIZE_MAX);
}

/** 筛选条件 + 分页 → 请求参数（空值不下发，避免后端把空串当条件） */
export function buildToolListQuery(filters: ToolFilters, pageNum?: number, pageSize?: number): ToolListQuery {
  const query: ToolListQuery = {
    pageNum: normalizePageNum(pageNum),
    pageSize: normalizePageSize(pageSize),
  };

  const keyword = filters.keyword.trim();
  if (keyword) {
    query.keyword = keyword;
  }

  if (filters.tagIds.length > 0) {
    query.tagIds = filters.tagIds.join(',');
  }

  const exts = filters.fileExts.map((item) => item.trim().toLowerCase()).filter((item) => item.length > 0);
  if (exts.length > 0) {
    query.fileExt = exts.join(',');
  }

  if (filters.scope) {
    query.scope = filters.scope;
  }
  // 类型筛选（契约 v1.23）：ALL 视为"不筛选"，不下发 —— 老后端不认识该参数也不会报错
  if (filters.toolType && filters.toolType !== 'ALL') {
    query.toolType = filters.toolType as 'FILE' | 'LINK';
  }
  if (filters.sortBy) {
    query.sortBy = filters.sortBy;
  }
  if (filters.sortOrder) {
    query.sortOrder = filters.sortOrder;
  }

  return query;
}

/** 是否处于筛选状态：用于区分"尚无任何工具"与"筛选无结果"两种空态 */
export function hasActiveFilters(filters: ToolFilters): boolean {
  return (
    filters.keyword.trim().length > 0 ||
    filters.tagIds.length > 0 ||
    filters.fileExts.length > 0 ||
    filters.scope.length > 0 ||
    (filters.toolType !== '' && filters.toolType !== 'ALL')
  );
}
