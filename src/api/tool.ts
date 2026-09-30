import { del, get, http, post, put } from './request';
import type { DownloadUrlVO, ID, MigrationTaskVO, PageQuery, PageResult, UserBriefVO } from './types';

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
  /** 下载地址签发（契约 v1.19 §9.5 新增）：鉴权通过后签发短时效地址（OSS 直链 / 后端令牌） */
  downloadUrl: (id: ID) => `/api/tools/${id}/download-url`,
  /** 存量迁移到对象存储（契约 v1.19 §9.9 新增）：发起 + 按 taskId 查询 */
  migrateToOss: '/api/tools/migrate-to-oss',
  migrateTask: (taskId: string) => `/api/tools/migrate-to-oss/${encodeURIComponent(taskId)}`,
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

/**
 * 下载地址归一化（**页面不得自行拼地址**）
 * - 对象存储签名直链是**绝对**地址，原样返回；
 * - 后端代理地址是**相对**路径（同源导航）；若配置了绝对 API 基址则补上，避免落到前端源。
 */
function resolveDownloadUrl(raw: string): string {
  if (/^(https?:)?\/\//i.test(raw)) {
    return raw;
  }
  const base = (http.defaults.baseURL ?? '').replace(/\/+$/, '');
  return base ? `${base}/${raw.replace(/^\/+/, '')}` : raw;
}

/**
 * 签发下载地址（契约 v1.19 §9.5；需权限 `tool:download`；不传 versionId = 最新版）
 *
 * 后端**先鉴权后签发**：对象存储落点 → 短时效签名直链（`direct=true`）；
 * 本地落点 → 后端令牌地址（`direct=false`）。调用方拿到后**同一动作**导航下载即可。
 * 失败口径同下载接口：资源已不存在 → `404`（`2004` / `2005`）；对象存储故障 → `2006`。
 */
export async function getDownloadUrl(id: ID, versionId?: ID): Promise<DownloadUrlVO> {
  const vo = await get<DownloadUrlVO>(TOOL_API.downloadUrl(id), versionId ? { versionId } : undefined);
  return { ...vo, url: resolveDownloadUrl(vo.url) };
}

/**
 * 发起存量迁移到对象存储（契约 v1.19 §9.9；需「全局设置」权限）
 *
 * **任务模型**：返回 `taskId` + `status`。本期后端**同步跑完**（`COMPLETED`）；
 * 将来改异步时（`PENDING` / `RUNNING`）由调用方用 {@link getMigrationStatus} 轮询 ——
 * **接口与前端结构均不变**（这是本设计的目的）。
 *
 * @param limit 单次迁移条数上限（可选，便于分批）；缺省 = 全部
 */
export function startMigration(limit?: number): Promise<MigrationTaskVO> {
  // limit 是后端 @RequestParam（URL 查询参数），不能放 JSON body —— 否则后端收不到、视为不限量
  return post<MigrationTaskVO>(
    TOOL_API.migrateToOss,
    undefined,
    limit != null ? { params: { limit } } : undefined,
  );
}

/** 查询迁移任务进度 / 结果（任务不存在或已过期 → `1004`） */
export function getMigrationStatus(taskId: string): Promise<MigrationTaskVO> {
  return get<MigrationTaskVO>(TOOL_API.migrateTask(taskId));
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
