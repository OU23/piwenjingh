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
  /** 版本级删除（契约 v1.22 §9.6：**物理删除、不可恢复**） */
  version: (id: ID, versionId: ID) => `/api/tools/${id}/versions/${versionId}`,
  /** 创建链接工具（契约 v1.23 §9.4 新增） */
  link: '/api/tools/link',
  /** 调整归属部门（契约 v1.23 §9.4 新增；权限 tool:assign） */
  dept: (id: ID) => `/api/tools/${id}/dept`,
} as const;

/** 工具类型（契约 v1.23）：FILE 文件工具 / LINK 链接工具 */
export type ToolType = 'FILE' | 'LINK';

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
  /** 上传人 ID（v1.21）：用于按行判定"本人可删除/恢复" */
  uploaderId: ID;
  deptName: string;
  fileSize: number;
  downloadCount: number;
  updateTime: string;
  /**
   * 工具类型（契约 v1.23）。后端灰度期可能缺失 → 由 {@link normalizeTool} 兜底为 `FILE`。
   * ⚠️ 页面请读归一化后的值，不要直接判断 `row.toolType === 'LINK'`（早期会漏判）。
   */
  toolType: ToolType;
  /** 链接工具的目标地址（仅 LINK 有值；FILE 为 null） */
  linkUrl: string | null;
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
  /**
   * 工具类型筛选（契约 v1.23 §9.2）：`ALL` / `FILE` / `LINK`。
   * `ALL` 时**不下发**（{@link buildToolListQuery} 保证），保持请求干净。
   */
  toolType?: ToolType | 'ALL';
  sortBy?: string;
  sortOrder?: string;
}

export interface ToolNameCheckDTO {
  name: string;
}

export interface ToolNameCheckVO {
  /** 当前登录人是否已有**未删除**的同名工具（v1.21：按上传人判定） */
  existed: boolean;
  toolId: ID | null;
  latestVersion: string | null;
  /** 是否可追加为新版本（existed=true 时恒为 true） */
  canAppend: boolean;
  /** 回收站里是否有「同上传人 + 同名」工具（v1.21） */
  inTrash: boolean;
  /** inTrash=true 时那个已删工具的 ID */
  trashToolId: ID | null;
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
  /**
   * 归属部门（契约 v1.23，可选）：**仅持 `tool:assign` 时生效**；
   * 不传 / 无权限 → 后端取登录用户部门（原行为）。
   */
  deptId?: ID;
  /** 由上传合并接口返回 */
  fileKey: string;
  fileName: string;
  fileSize: number;
}

/** 创建链接工具入参（契约 v1.23 §9.4）：无文件、无版本 */
export interface ToolLinkSaveDTO {
  name: string;
  /** `http/https`，≤512（前后端都要校验） */
  linkUrl: string;
  intro: string;
  readme?: string;
  platform?: string;
  dependency?: string;
  tagIds?: ID[];
  scope: string;
  /** 仅持 `tool:assign` 时生效 */
  deptId?: ID;
}

/** 调整归属部门入参（契约 v1.23 §9.4） */
export interface ToolDeptDTO {
  deptId: ID;
}

export interface VersionSaveDTO {
  version: string;
  fileKey: string;
  fileName: string;
  fileExt: string;
  fileSize: number;
}

export interface ToolUpdateDTO {
  /** 工具名称：**本期编辑页不改名**，恒传原名（后端 v1.23 保持现状） */
  name: string;
  /** 版本号：编辑页不提供；传原值即可（后端 v1.23 起忽略该字段） */
  version?: string;
  intro: string;
  readme?: string;
  /** 文件格式：编辑页只读；传原值（LINK 为空串，后端 v1.23 起非必填） */
  fileExt?: string;
  platform?: string;
  dependency?: string;
  tagIds?: ID[];
  scope: string;
  /** 链接地址（契约 v1.23，**仅 LINK 可传**；FILE 传了后端返回 `1003`） */
  linkUrl?: string;
}

export interface ToolCreateResult {
  toolId: ID;
  versionId: ID;
}

/**
 * 归一化 v1.23 新增字段（**后端灰度期兜底，清除 mock 后保留**）
 *
 * 后端未上线 `toolType` / `linkUrl` 时，响应里这两个字段是 `undefined`。
 * 直接判断 `row.toolType === 'LINK'` 会把链接工具误判成文件工具（下载按钮、版本列全错），
 * 因此所有出口统一走这里：缺 `toolType` → `FILE`，缺 `linkUrl` → `null`。
 */
export function normalizeTool<T extends ToolVO>(tool: T): T {
  return {
    ...tool,
    toolType: tool.toolType === 'LINK' ? 'LINK' : 'FILE',
    linkUrl: tool.linkUrl ?? null,
  } as T;
}

export async function listTools(query: ToolListQuery): Promise<PageResult<ToolVO>> {
  const page = await get<PageResult<ToolVO>>(TOOL_API.base, query as Record<string, unknown>);
  return { ...page, list: page.list.map(normalizeTool) };
}

export async function getToolDetail(id: ID): Promise<ToolDetailVO> {
  return normalizeTool(await get<ToolDetailVO>(TOOL_API.byId(id)));
}

export function listToolVersions(id: ID): Promise<ToolVersionVO[]> {
  return get<ToolVersionVO[]>(TOOL_API.versions(id));
}

/**
 * 同名检查（契约 §9.4 v1.21）：按「名称 + 当前上传人」判定。
 * - `existed=true` → 走"追加版本"；
 * - `inTrash=true` → 提示"回收站已有同名工具：删除旧工具 / 恢复旧工具"；
 * - 都不命中 → 正常新建。
 */
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

/**
 * 创建链接工具（契约 v1.23）：无文件、无版本。
 * 名称为空/超长、URL 非 `http(s)` 等非法入参由后端返回 `1003`；前端提交前先本地校验。
 */
export function createLinkTool(data: ToolLinkSaveDTO): Promise<{ toolId: ID }> {
  return post<{ toolId: ID }>(TOOL_API.link, data);
}

/**
 * 调整工具归属部门（契约 v1.23）：需 `tool:assign`。
 * 与内容编辑**分开调用**（编辑页先保存内容、再调本接口）。
 */
export function updateToolDept(id: ID, deptId: ID): Promise<null> {
  return put<null>(TOOL_API.dept(id), { deptId } as ToolDeptDTO);
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

/**
 * 删除单个历史版本（契约 v1.22：**物理删除、不可恢复**，不进回收站）。
 * 不允许删最后一个版本（后端 `1005`）；删最新版时后端自动回退 `latest_*`。
 */
export function deleteToolVersion(id: ID, versionId: ID): Promise<null> {
  return del<null>(TOOL_API.version(id, versionId));
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
