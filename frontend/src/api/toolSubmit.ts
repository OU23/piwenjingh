import type { UploadResult } from './chunkUpload';
import { checkToolName, createTool, createToolVersion } from './tool';
import type { ID } from './types';
import { fileExtFromName, type ToolMetaMode, type ToolMetaValues } from '@/utils/toolMetaValidation';

/**
 * 上传完成后的落库提交（P3 T6）
 *
 * 同名规则（契约 §9.4 **v1.21**）：名称唯一性收窄到「上传人维度」，**同名转版本已恢复**。
 * - `existed = true`（我自己的同名未删工具）→ 直接**追加为新版本**；
 * - `inTrash = true`（回收站里有我的同名工具）→ 返回 `in-trash`，由页面提示
 *   「删除旧工具 / 恢复旧工具」后再继续；
 * - 都不命中 → 正常新建。
 *
 * 别人的同名工具**不再命中**（GitHub 模式：同名不同人可并存）。
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
      /** 回收站里已有同上传人同名工具：需先"删除旧工具"或"恢复旧工具"（v1.21） */
      kind: 'in-trash';
      toolId: ID | null;
      latestVersion: string | null;
    }
  | {
      /**
       * @deprecated v1.21 起**不再产生**（同名转版本已恢复）。
       * 保留类型以兼容既有页面分支；后续清理时可整体移除。
       */
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

  // 新建：先查同名（按上传人）→ 命中则追加版本 / 提示回收站，否则新建
  const checked = await checkToolName({ name: values.name.trim() });

  // ① 我自己的同名未删工具 → 追加为新版本（v1.21 恢复的同名转版本）
  if (checked.existed && checked.toolId) {
    return appendVersion(checked.toolId, values, upload);
  }
  // ② 回收站里有我的同名工具 → 交由页面提示"删除旧工具 / 恢复旧工具"
  if (checked.inTrash) {
    return { kind: 'in-trash', toolId: checked.trashToolId ?? null, latestVersion: checked.latestVersion ?? null };
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
    // M-25：仅持 tool:assign 时页面才会填；空 = 不指定，后端取登录用户部门
    deptId: values.deptId || undefined,
    fileKey: upload.fileKey,
    fileName: upload.fileName,
    fileSize: upload.fileSize,
  });

  return { kind: 'created', toolId: created.toolId, versionId: created.versionId };
}
