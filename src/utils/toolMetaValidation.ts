/**
 * 工具元数据表单的取值与校验（纯函数，便于单测）
 * 字段与长度上限来源：docs/接口契约.md §9.4（ToolSaveDTO / VersionSaveDTO）
 */

export type ToolMetaMode = 'create' | 'version' | 'edit';

export interface ToolMetaValues {
  name: string;
  version: string;
  intro: string;
  readme: string;
  fileExt: string;
  platform: string;
  dependency: string;
  tagIds: string[];
  scope: string;
}

/** 长度上限（与契约一致，改这里必须同步契约） */
export const TOOL_META_LIMITS = {
  name: 100,
  version: 20,
  intro: 200,
  readme: 10000,
  dependency: 500,
} as const;

export function createEmptyToolMeta(): ToolMetaValues {
  return {
    name: '',
    version: '',
    intro: '',
    readme: '',
    fileExt: '',
    platform: '',
    dependency: '',
    tagIds: [],
    scope: '',
  };
}

/** 返回第一条不满足的提示；全部通过返回空字符串 */
export function validateToolMeta(mode: ToolMetaMode, values: ToolMetaValues): string {
  const version = values.version.trim();

  // 追加版本：只提交版本号（其余信息属工具级，已在库中）
  if (mode === 'version') {
    if (!version) {
      return '请输入版本号';
    }
    if (version.length > TOOL_META_LIMITS.version) {
      return `版本号不能超过 ${TOOL_META_LIMITS.version} 个字符`;
    }
    return '';
  }

  const name = values.name.trim();

  if (!name) {
    return '请输入工具名称';
  }
  if (name.length > TOOL_META_LIMITS.name) {
    return `工具名称不能超过 ${TOOL_META_LIMITS.name} 个字符`;
  }
  if (!version) {
    return '请输入版本号';
  }
  if (version.length > TOOL_META_LIMITS.version) {
    return `版本号不能超过 ${TOOL_META_LIMITS.version} 个字符`;
  }
  if (!values.intro.trim()) {
    return '请输入简介';
  }
  if (values.intro.length > TOOL_META_LIMITS.intro) {
    return `简介不能超过 ${TOOL_META_LIMITS.intro} 个字符`;
  }
  if (!values.fileExt.trim()) {
    // 格式由文件名自动识别、不可手选：为空说明文件名没有扩展名，只能返回重选
    return '无法从文件名识别文件格式，请返回重新选择带扩展名的文件';
  }
  if (values.dependency.length > TOOL_META_LIMITS.dependency) {
    return `依赖说明不能超过 ${TOOL_META_LIMITS.dependency} 个字符`;
  }
  if (values.readme.length > TOOL_META_LIMITS.readme) {
    return `使用说明不能超过 ${TOOL_META_LIMITS.readme} 个字符`;
  }
  if (!values.scope) {
    return '请选择可见范围';
  }
  return '';
}

/** 从文件名推导格式（小写、去掉点）；无法识别时返回空串 */
export function fileExtFromName(fileName: string): string {
  const matched = /\.([A-Za-z0-9]+)$/.exec(fileName.trim());
  return matched?.[1] ? matched[1].toLowerCase() : '';
}
