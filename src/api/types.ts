/** 通用类型与业务码（来源：docs/接口契约.md §1.2 / §1.5 / §十二） */

/** ID 统一用字符串传输，避免前端精度丢失（契约 §1.6） */
export type ID = string;

/** 统一响应体（契约 §1.2） */
export interface ApiResult<T = unknown> {
  code: number;
  message: string;
  data: T | null;
}

/** 分页请求参数（契约 §1.5） */
export interface PageQuery {
  pageNum?: number;
  pageSize?: number;
}

/** 分页响应（契约 §1.5） */
export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

/**
 * 工具简述（契约 §6.1 `ToolBriefVO`，v1.7 新增）
 * 用于跨部门授权关系的**回显**：带上名称与归属部门，避免只有 ID 时"已选项不在当前搜索结果中显示不出名字"。
 */
export interface ToolBriefVO {
  id: ID;
  name: string;
  deptName: string;
  latestVersion: string;
  /** DEPT / PUBLIC（契约 v1.14 §6.1 新增，T8 候选过滤依赖） */
  scope: string;
}

/**
 * 用户简述（契约 §6.1 `UserBriefVO`，v1.7 新增）
 * 工具侧"授权给哪些用户"的回显用；重名时靠 `username` 区分。
 */
export interface UserBriefVO {
  id: ID;
  username: string;
  realName: string;
  deptName: string;
}

/** 导入单行失败明细（契约 §6.2，v1.9 新增） */
export interface ImportError {
  /** **Excel 实际行号**（用户在 Excel 中看到的那一行），便于直接定位修改 */
  rowNum: number;
  /** 面向用户的中文失败原因 */
  message: string;
}

/**
 * 批量导入结果（契约 §6.2，v1.9 新增）
 * ⚠️ **「跳过」≠「失败」**：已存在（部门名 / 账号）计入 `skipCount`，**不进错误 Excel**；
 * 只有校验不通过才计入 `failCount` 并出现在 `errors` / 错误文件里。
 */
export interface ImportResultVO {
  /** 参与处理的数据行数（已排除表头行与全空行） */
  total: number;
  successCount: number;
  /** 跳过行数（系统中已存在，**不算失败**） */
  skipCount: number;
  /** 失败行数（校验不通过） */
  failCount: number;
  /** 本次**自动创建**的部门名称（仅用户导入可能非空） */
  createdDeptNames: string[];
  /** 失败明细（后端最多返回前 100 条） */
  errors: ImportError[];
  /** 有失败行时为错误 Excel 的 Base64（xlsx 字节）；无失败为 `null` */
  errorFileBase64: string | null;
  /** 错误 Excel 的建议文件名；无失败为 `null` */
  errorFileName: string | null;
}

/** 业务码（契约 §十二）：0 成功；非 0 为业务失败（HTTP 仍为 200） */
export const BizCode = {
  SUCCESS: 0,
  SYSTEM_ERROR: 1000,
  UNAUTHORIZED: 1001,
  FORBIDDEN: 1002,
  PARAM_INVALID: 1003,
  NOT_FOUND: 1004,
  CONFLICT: 1005,
  LOGIN_FAILED: 1006,
  ALREADY_INITIALIZED: 1007,
  /** v1.8 新增：账号已在其他设备登录（**仅登录接口**；HTTP 200 业务失败，前端需二次确认） */
  ONLINE_CONFLICT: 1008,
  FILE_TOO_LARGE: 2001,
  CHUNK_INVALID: 2002,
  STORAGE_UNAVAILABLE: 2003,
  TOOL_NOT_FOUND: 2004,
  FILE_KEY_INVALID: 2005,
} as const;

export type BizCodeValue = (typeof BizCode)[keyof typeof BizCode];
