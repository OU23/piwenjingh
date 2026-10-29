import { download, get, post, put, type DownloadPayload } from './request';
import type { ID, ImportResultVO } from './types';

/** 部门（docs/接口契约.md §四） */
const DEPT_API = {
  base: '/api/depts',
  byId: (id: ID) => `/api/depts/${id}`,
  status: (id: ID) => `/api/depts/${id}/status`,
  /** 导入导出（契约 v1.9 §6.2） */
  importTemplate: '/api/depts/import-template',
  importFile: '/api/depts/import',
  exportFile: '/api/depts/export',
} as const;

export interface DeptVO {
  id: ID;
  name: string;
  remark: string | null;
  /** 1 启用 / 0 停用 */
  status: number;
  userCount: number;
  createTime: string;
}

export interface DeptSaveDTO {
  name: string;
  remark?: string;
}

export interface DeptStatusDTO {
  status: number;
}

export interface DeptListQuery {
  status?: number;
}

/** 部门列表（全量，不分页） */
export function listDepts(params?: DeptListQuery): Promise<DeptVO[]> {
  return get<DeptVO[]>(DEPT_API.base, params as Record<string, unknown> | undefined);
}

/** 新建部门 */
export function createDept(data: DeptSaveDTO): Promise<ID> {
  return post<ID>(DEPT_API.base, data);
}

/** 编辑部门 */
export function updateDept(id: ID, data: DeptSaveDTO): Promise<null> {
  return put<null>(DEPT_API.byId(id), data);
}

/** 启用 / 停用部门（部门下有用户时不允许停用） */
export function updateDeptStatus(id: ID, data: DeptStatusDTO): Promise<null> {
  return put<null>(DEPT_API.status(id), data);
}

/* ───────────── 批量导入 / 导出（契约 §6.2，v1.9 新增） ───────────── */

/** 下载部门导入模板（**Excel 文件流**，走鉴权下载通道：带 `Authorization`，非静态直链） */
export function downloadDeptTemplate(): Promise<DownloadPayload> {
  return download(DEPT_API.importTemplate);
}

/**
 * 批量导入部门
 * - 走**普通 multipart**（Excel 属小文件，不复用分片上传链路）
 * - 字段名**固定为 `file`**；**不手工设置 `Content-Type`**（交给浏览器带 boundary）
 */
export function importDepts(file: File): Promise<ImportResultVO> {
  const form = new FormData();
  form.append('file', file);
  return post<ImportResultVO>(DEPT_API.importFile, form);
}

/** 导出部门（Excel 文件流；`status?` 可选筛选，未传为全量） */
export function exportDepts(query?: DeptListQuery): Promise<DownloadPayload> {
  return download(DEPT_API.exportFile, query as Record<string, unknown> | undefined);
}
