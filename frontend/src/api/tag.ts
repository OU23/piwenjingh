import { del, get, post, put } from './request';
import type { ID } from './types';

/** 标签（docs/接口契约.md §七） */
const TAG_API = {
  base: '/api/tags',
  byId: (id: ID) => `/api/tags/${id}`,
} as const;

export interface TagVO {
  id: ID;
  name: string;
  toolCount: number;
  createTime: string;
}

export interface TagSaveDTO {
  name: string;
}

export interface TagListQuery {
  keyword?: string;
}

/** 标签列表（全量，供筛选与勾选） */
export function listTags(params?: TagListQuery): Promise<TagVO[]> {
  return get<TagVO[]>(TAG_API.base, params as Record<string, unknown> | undefined);
}

export function createTag(data: TagSaveDTO): Promise<ID> {
  return post<ID>(TAG_API.base, data);
}

export function updateTag(id: ID, data: TagSaveDTO): Promise<null> {
  return put<null>(TAG_API.byId(id), data);
}

/** 删除标签：仅解除与工具的关联，不删除工具 */
export function deleteTag(id: ID): Promise<null> {
  return del<null>(TAG_API.byId(id));
}
