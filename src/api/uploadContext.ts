import { getSettings } from './setting';
import { listTags, type TagVO } from './tag';
import { getToolDetail } from './tool';
import type { ID } from './types';

/**
 * 上传页的上下文数据（单文件上限 / 标签字典 / 追加版本时的目标工具）
 * 集中在一处加载：任一项失败都不阻断其余项（上限读不到时页面提示，标签为空即可）。
 */

export interface UploadTargetTool {
  id: ID;
  name: string;
  fileExt: string;
  platform: string;
  dependency: string;
  intro: string;
  scope: string;
  tagIds: string[];
}

export interface UploadContext {
  /** 单文件上限（字节）；0 表示未能读取 */
  maxFileSize: number;
  tags: TagVO[];
  tool: UploadTargetTool | null;
  /** 上限读取失败的原因，用于页面提示 */
  maxFileSizeError: string;
  /** 标签字典读取失败的原因，用于页面提示 */
  tagsError: string;
}

export async function loadUploadContext(toolId?: ID): Promise<UploadContext> {
  const [settingsResult, tagsResult, toolResult] = await Promise.allSettled([
    getSettings(),
    listTags(),
    toolId ? getToolDetail(toolId) : Promise.resolve(null),
  ]);

  return {
    maxFileSize: settingsResult.status === 'fulfilled' ? settingsResult.value.maxFileSize : 0,
    maxFileSizeError:
      settingsResult.status === 'rejected'
        ? settingsResult.reason instanceof Error
          ? settingsResult.reason.message
          : '无法读取单文件上限'
        : '',
    tags: tagsResult.status === 'fulfilled' ? tagsResult.value : [],
    tagsError:
      tagsResult.status === 'rejected'
        ? tagsResult.reason instanceof Error
          ? tagsResult.reason.message
          : '无法读取标签字典'
        : '',
    tool:
      toolResult.status === 'fulfilled' && toolResult.value
        ? {
            id: toolResult.value.id,
            name: toolResult.value.name,
            fileExt: toolResult.value.fileExt,
            platform: toolResult.value.platform ?? '',
            dependency: toolResult.value.dependency ?? '',
            intro: toolResult.value.intro,
            scope: toolResult.value.scope,
            tagIds: toolResult.value.tags.map((tag) => tag.id),
          }
        : null,
  };
}
