import type { UploadResult } from './chunkUpload';
import { checkToolName, createTool, createToolVersion } from './tool';
import type { ID } from './types';
import { fileExtFromName, type ToolMetaMode, type ToolMetaValues } from '@/utils/toolMetaValidation';

/**
 * 上传完成后的落库提交（P3 T6）
 *
 * 同名规则（契约 §9.4 + `接口补充说明.md` N-01）：**名称全局唯一**（不区分部门）。
 * - 命中同名 → **一律阻止提交**（返回 `name-blocked`，前端提示改名、文件无需重传）。
 *   ⚠️ **临时偏离（2026-09-29）**：契约 N-01 原允许 `canAppend = true` 时**静默追加为新版本**；
 *   本期**关闭该分支**（避免不同功能的同名工具被误并为一个工具的版本历史），
 *   待版本管理实现后恢复。故 `canAppend` 当前仅作信息字段，不再决定分支。
 * - 版本模式（从工具详情进入）仍走追加版本接口。
 *
 * 三条路径必须由这里统一决定，页面不得自行判断。
 */

export type SubmitOutcome =
  | { kind: 'created'; toolId: ID; versionId: ID }
  | {
      kind: 'version';
      toolId: ID;
      versionId: ID;
    }
  | {
      /** 名称已被占用（任何范围）：需改名后重新提交（文件无需重传） */
      kind: 'name-blocked';
      latestVersion: string | null;
    };

export interface SubmitToolOptions {
  mode: ToolMetaMode;
  values: ToolMetaValues;
  upload: UploadResult;
  /** 追加版本模式（从工具详情进入）必传 */
  toolId?: ID;
}

/** 追加新版本到已有工具 */
async function appendVersion(
  toolId: ID,
  values: ToolMetaValues,
  upload: UploadResult,
): Promise<SubmitOutcome> {
  const versionId = await createToolVersion(toolId, {
    version: values.version.trim(),
    fileKey: upload.fileKey,
    fileName: upload.fileName,
    fileExt: values.fileExt.trim().toLowerCase() || fileExtFromName(upload.fileName),
    fileSize: upload.fileSize,
  });

  return { kind: 'version', toolId, versionId };
}

export async function submitTool(options: SubmitToolOptions): Promise<SubmitOutcome> {
  const { mode, values, upload } = options;

  // 从工具详情进入：工具已确定，直接追加版本
  if (mode === 'version') {
    if (!options.toolId) {
      throw new Error('缺少目标工具，无法追加版本');
    }
    return appendVersion(options.toolId, values, upload);
  }

  // 新建：先查同名 → 命中即阻止（临时关闭"同名转版本"，见文件头注释）
  const checked = await checkToolName({ name: values.name.trim() });
  if (checked.existed) {
    return { kind: 'name-blocked', latestVersion: checked.latestVersion ?? null };
  }

  const created = await createTool({
    name: values.name.trim(),
    version: values.version.trim(),
    intro: values.intro.trim(),
    readme: values.readme.trim() || undefined,
    fileExt: values.fileExt.trim().toLowerCase(),
    platform: values.platform.trim() || undefined,
    dependency: values.dependency.trim() || undefined,
    tagIds: values.tagIds.length > 0 ? values.tagIds : undefined,
    scope: values.scope,
    fileKey: upload.fileKey,
    fileName: upload.fileName,
    fileSize: upload.fileSize,
  });

  return { kind: 'created', toolId: created.toolId, versionId: created.versionId };
}
