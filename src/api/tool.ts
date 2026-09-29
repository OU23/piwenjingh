import { del, download, get, http, post, put, saveBlob, type DownloadPayload } from './request';
import type { ID, PageQuery, PageResult, UserBriefVO } from './types';

/** 工具与版本（docs/接口契约.md §九） */
const TOOL_API = {
  base: '/api/tools',
  byId: (id: ID) => `/api/tools/${id}`,
  versions: (id: ID) => `/api/tools/${id}/versions`,
  checkName: '/api/tools/check-name',
  deleted: '/api/tools/deleted',
  restore: (id: ID) => `/api/tools/${id}/restore`,
  /** 批量软删除（进回收站，契约 v1.5 §9.6） */
  batchDelete: '/api/tools/batch-delete',
  /** 批量彻底删除（物理删文件，仅作用于已删除工具，契约 v1.5 §9.6） */
  batchPurge: '/api/tools/batch-purge',
  download: (id: ID) => `/api/tools/${id}/download`,
  /** 方案 2-B：换取短时效下载令牌（见 docs/内部-大文件下载方案.md §6） */
  downloadToken: (id: ID) => `/api/tools/${id}/download-token`,
  /** 工具侧授权入口（契约 v1.7 §9.8） */
  grants: (id: ID) => `/api/tools/${id}/grants`,
} as const;

export interface TagBrief {
  id: ID;
  name: string;
}

/** 列表项（只含最新版本信息） */
export interface ToolVO {
  id: ID;
  name: string;
  latestVersion: string;
  intro: string;
  fileExt: string;
  /** DEPT / PUBLIC */
  scope: string;
  tags: TagBrief[];
  uploaderName: string;
  deptName: string;
  fileSize: number;
  downloadCount: number;
  updateTime: string;
}

export interface ToolVersionVO {
  id: ID;
  version: string;
  fileName: string;
  fileExt: string;
  fileSize: number;
  uploaderName: string;
  uploadTime: string;
  downloadCount: number;
  isLatest: boolean;
}

/** 详情 = 列表项全部字段 + 以下字段 */
export interface ToolDetailVO extends ToolVO {
  readme: string | null;
  platform: string | null;
  dependency: string | null;
  uploaderId: ID;
  deptId: ID;
  createTime: string;
  versions: ToolVersionVO[];
}

export interface ToolListQuery extends PageQuery {
  keyword?: string;
  /** 标签 ID，多个用英文逗号分隔 */
  tagIds?: string;
  /** 文件格式，小写，多个用英文逗号分隔 */
  fileExt?: string;
  scope?: string;
  sortBy?: string;
  sortOrder?: string;
}

export interface ToolNameCheckDTO {
  name: string;
}

export interface ToolNameCheckVO {
  existed: boolean;
  toolId: ID | null;
  latestVersion: string | null;
  /** 同名工具是否在可见范围内（本部门或 PUBLIC）：true 可追加版本，false 需改名（补充说明 N-01） */
  canAppend: boolean;
}

export interface ToolSaveDTO {
  name: string;
  version: string;
  intro: string;
  readme?: string;
  fileExt: string;
  platform?: string;
  dependency?: string;
  tagIds?: ID[];
  scope: string;
  /** 由上传合并接口返回 */
  fileKey: string;
  fileName: string;
  fileSize: number;
}

export interface VersionSaveDTO {
  version: string;
  fileKey: string;
  fileName: string;
  fileExt: string;
  fileSize: number;
}

export interface ToolUpdateDTO {
  name: string;
  version: string;
  intro: string;
  readme?: string;
  fileExt: string;
  platform?: string;
  dependency?: string;
  tagIds?: ID[];
  scope: string;
}

export interface ToolCreateResult {
  toolId: ID;
  versionId: ID;
}

/**
 * 方案 2-B：短时效下载令牌（非安全上下文原生下载用，见 docs/内部-大文件下载方案.md §6）
 * - 后端把令牌绑定到「当前用户 + 工具 + 版本」，有效期建议 ≤ 60s；
 * - 前端拼成 `.../download?token=xxx` 后用原生 `<a>` 触发，浏览器原生流式落盘、无内存上限。
 */
export interface DownloadTokenVO {
  token: string;
  /** 有效期（秒），仅供前端预判/提示 */
  expiresIn: number;
}

export function listTools(query: ToolListQuery): Promise<PageResult<ToolVO>> {
  return get<PageResult<ToolVO>>(TOOL_API.base, query as Record<string, unknown>);
}

export function getToolDetail(id: ID): Promise<ToolDetailVO> {
  return get<ToolDetailVO>(TOOL_API.byId(id));
}

export function listToolVersions(id: ID): Promise<ToolVersionVO[]> {
  return get<ToolVersionVO[]>(TOOL_API.versions(id));
}

/** 同名检查：命中则前端阻止提交（临时关闭"同名转版本"，见 toolSubmit.ts 文件头注释） */
export function checkToolName(data: ToolNameCheckDTO): Promise<ToolNameCheckVO> {
  return post<ToolNameCheckVO>(TOOL_API.checkName, data);
}

/** 新建工具（含首个版本） */
export function createTool(data: ToolSaveDTO): Promise<ToolCreateResult> {
  return post<ToolCreateResult>(TOOL_API.base, data);
}

/** 为已有工具追加新版本 */
export function createToolVersion(id: ID, data: VersionSaveDTO): Promise<ID> {
  return post<ID>(TOOL_API.versions(id), data);
}

export function updateTool(id: ID, data: ToolUpdateDTO): Promise<null> {
  return put<null>(TOOL_API.byId(id), data);
}

/** 删除工具（软删除，可从"已删除"列表恢复） */
export function deleteTool(id: ID): Promise<null> {
  return del<null>(TOOL_API.byId(id));
}

export function listDeletedTools(query: ToolListQuery): Promise<PageResult<ToolVO>> {
  return get<PageResult<ToolVO>>(TOOL_API.deleted, query as Record<string, unknown>);
}

export function restoreTool(id: ID): Promise<null> {
  return post<null>(TOOL_API.restore(id));
}

/** 批量删除 / 彻底删除的入参（契约 v1.5 §9.6） */
export interface ToolBatchDTO {
  ids: ID[];
}

/** 批量软删除（进回收站，可恢复） */
export function batchDeleteTools(ids: ID[]): Promise<null> {
  return post<null>(TOOL_API.batchDelete, { ids } as ToolBatchDTO);
}

/** 批量彻底删除（物理删除、不可恢复；单个也传单元素数组） */
export function purgeTools(ids: ID[]): Promise<null> {
  return post<null>(TOOL_API.batchPurge, { ids } as ToolBatchDTO);
}

/** 清空回收站（彻底删除全部已删除工具） */
export function clearDeletedTools(): Promise<null> {
  return del<null>(TOOL_API.deleted);
}

/** 下载工具文件（带令牌请求；不传 versionId 表示最新版） */
export function downloadTool(id: ID, versionId?: ID): Promise<DownloadPayload> {
  return download(TOOL_API.download(id), versionId ? { versionId } : undefined);
}

/** 下载并触发保存（统一入口，页面不再自行拼下载逻辑） */
export async function downloadToolToFile(id: ID, versionId?: ID): Promise<void> {
  const payload = await downloadTool(id, versionId);
  saveBlob(payload.blob, payload.fileName);
}

/**
 * 下载地址（供流式下载使用）
 * 流式下载走 fetch 而非 axios 实例，因此需在此补上可配置基址——页面不得自行拼地址。
 */
export function toolDownloadUrl(id: ID, versionId?: ID): string {
  const base = (http.defaults.baseURL ?? '').replace(/\/+$/, '');
  const query = versionId ? `?versionId=${encodeURIComponent(versionId)}` : '';
  return `${base}${TOOL_API.download(id)}${query}`;
}

/**
 * 方案 2-B：换取短时效下载令牌（需权限 `tool:download`；不传 versionId = 最新版）
 * 后端未就绪时该请求会失败，调用方回退整包读入降级路径。
 */
export function fetchDownloadToken(id: ID, versionId?: ID): Promise<DownloadTokenVO> {
  return post<DownloadTokenVO>(TOOL_API.downloadToken(id), versionId ? { versionId } : {});
}

/**
 * 方案 2-B：把短时效令牌拼进下载地址，供原生 `<a>` 下载携带鉴权。
 * 仍走带令牌的下载接口（非静态直链），仅鉴权载体从请求头换成查询参数。
 */
export function toolDownloadUrlWithToken(id: ID, token: string, versionId?: ID): string {
  const url = toolDownloadUrl(id, versionId);
  const separator = url.includes('?') ? '&' : '?';
  return `${url}${separator}token=${encodeURIComponent(token)}`;
}

/**
 * 工具侧「授权」（契约 §9.8，v1.7 新增）
 * 与用户侧 `/api/users/{id}/tool-grants` 操作**同一张** `tb_tool_grant`：任一侧改动，另一侧立即生效。
 */
export interface ToolGrantUsersDTO {
  /** 用户 ID **全量列表**（覆盖式保存；空数组 = 清空全部授权） */
  userIds: ID[];
}

/** 查询该工具已授权给哪些用户（工具侧入口） */
export function listToolGrants(id: ID): Promise<UserBriefVO[]> {
  return get<UserBriefVO[]>(TOOL_API.grants(id));
}

/** 保存该工具的授权用户（全量覆盖） */
export function saveToolGrants(id: ID, data: ToolGrantUsersDTO): Promise<null> {
  return put<null>(TOOL_API.grants(id), data);
}
