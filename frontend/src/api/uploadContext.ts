import { getSettings } from './setting';
import { getUploadMode, type UploadMode } from './uploadMode';
import { listTags, type TagVO } from './tag';
import { getToolDetail } from './tool';
import type { ID } from './types';

/**
 * 上传页的上下文数据（上传模式 / 分片大小 / 单文件上限 / 标签字典 / 追加版本时的目标工具）
 * 集中在一处加载：任一项失败都不阻断其余项（上限读不到时页面提示，标签为空即可）。
 *
 * ⚠️ `mode` 读取失败时**兜底 `server`**（经服务器中转链路始终可用），不阻断上传主流程。
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
  /** 上传通道（分流依据，契约 v1.20 §8.5）；读取失败时兜底 `server` */
  mode: UploadMode;
  /** 生效分片大小（字节，`local` 模式即切片大小）；无法获取时为 0 */
  partSize: number;
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
  const [settingsResult, modeResult, tagsResult, toolResult] = await Promise.allSettled([
    getSettings(),
    getUploadMode(),
    listTags(),
    toolId ? getToolDetail(toolId) : Promise.resolve(null),
  ]);

  // 分片大小：优先取 mode 的权威值；mode 失败时退回设置里的 chunkSize（同源，二者都由后端下发）
  const partSize =
    modeResult.status === 'fulfilled'
      ? modeResult.value.partSize
      : settingsResult.status === 'fulfilled'
        ? (settingsResult.value.chunkSize ?? 0)
        : 0;

  return {
    mode: modeResult.status === 'fulfilled' ? modeResult.value.mode : 'server',
    partSize,
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
