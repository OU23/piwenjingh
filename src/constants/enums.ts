/** 枚举与固定值（取值来源：docs/接口契约.md §十三、docs/数据模型.md §五） */

/** 可见范围（契约 §13.2） */
export const SCOPE = {
  DEPT: 'DEPT',
  PUBLIC: 'PUBLIC',
} as const;

export type ScopeValue = (typeof SCOPE)[keyof typeof SCOPE];

/** 显示文案映射（键与 SCOPE 取值一一对应，Record 保证不会漏） */
export const SCOPE_LABEL: Record<ScopeValue, string> = {
  DEPT: '本部门可见',
  PUBLIC: '全员可见',
};

/** 通用状态（契约 §13.3） */
export const STATUS = {
  ENABLED: 1,
  DISABLED: 0,
} as const;

export const STATUS_LABEL: Record<number, string> = {
  1: '启用',
  0: '停用',
};

/** 排序字段（契约 §13.4） */
export const SORT_BY = {
  CREATE_TIME: 'createTime',
  DOWNLOAD_COUNT: 'downloadCount',
  NAME: 'name',
} as const;

export type SortByValue = (typeof SORT_BY)[keyof typeof SORT_BY];

export const SORT_BY_LABEL: Record<SortByValue, string> = {
  createTime: '上传时间',
  downloadCount: '下载次数',
  name: '名称',
};

/** 排序方向（契约 §13.4） */
export const SORT_ORDER = {
  ASC: 'asc',
  DESC: 'desc',
} as const;

export type SortOrderValue = (typeof SORT_ORDER)[keyof typeof SORT_ORDER];

/** 分页默认值（契约 §1.5） */
export const PAGE_SIZE_DEFAULT = 20;
export const PAGE_SIZE_MAX = 100;
