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
  /**
   * 归属部门（M-25）：**仅持 `tool:assign` 时可改**；空串 = 不指定（后端取登录用户部门）。
   * `edit` 模式回显当前部门，提交时仅在变化时单独调部门接口。
   */
  deptId: string;
  /** 链接地址（M-25，仅 LINK 类型使用；FILE 恒为空串） */
  linkUrl: string;
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
    deptId: '',
    linkUrl: '',
  };
}

/**
 * 链接地址校验（M-25）：**协议白名单**（只允许 http/https），长度 ≤512。
 * 前端提示用；后端必须再校验一次（前端校验只是体验）。
 */
export function validateLinkUrl(raw: string): string {
  const url = raw.trim();
  if (!url) {
    return '请输入链接地址';
  }
  if (url.length > 512) {
    return '链接地址不能超过 512 个字符';
  }
  if (!/^https?:\/\//i.test(url)) {
    return '链接必须以 http:// 或 https:// 开头';
  }
  return '';
}

/** 校验选项（M-25）：`linkMode` = 链接工具，无版本号/文件格式，改校验 URL */
export interface ToolMetaValidateOptions {
  linkMode?: boolean;
}

/** 返回第一条不满足的提示；全部通过返回空字符串 */
export function validateToolMeta(
  mode: ToolMetaMode,
  values: ToolMetaValues,
  options: ToolMetaValidateOptions = {},
): string {
  const version = values.version.trim();
  const linkMode = options.linkMode === true;

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

  // 编辑（M-25）：名称 / 版本号 / 文件格式**均为只读或不提供**，不得参与校验
  // （LINK 工具这两项为空，若沿用新建分支会永远报"请输入版本号"，编辑直接不可用）
  if (mode === 'edit') {
    if (!values.intro.trim()) {
      return '请输入简介';
    }
    if (values.intro.length > TOOL_META_LIMITS.intro) {
      return `简介不能超过 ${TOOL_META_LIMITS.intro} 个字符`;
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
    if (linkMode) {
      return validateLinkUrl(values.linkUrl);
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
  // 链接工具没有版本号 / 文件格式（M-25）
  if (!linkMode) {
    if (!version) {
      return '请输入版本号';
    }
    if (version.length > TOOL_META_LIMITS.version) {
      return `版本号不能超过 ${TOOL_META_LIMITS.version} 个字符`;
    }
  }
  if (!values.intro.trim()) {
    return '请输入简介';
  }
  if (values.intro.length > TOOL_META_LIMITS.intro) {
    return `简介不能超过 ${TOOL_META_LIMITS.intro} 个字符`;
  }
  if (!linkMode && !values.fileExt.trim()) {
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
  if (linkMode) {
    return validateLinkUrl(values.linkUrl);
  }
  return '';
}

/** 从文件名推导格式（小写、去掉点）；无法识别时返回空串 */
export function fileExtFromName(fileName: string): string {
  const matched = /\.([A-Za-z0-9]+)$/.exec(fileName.trim());
  return matched?.[1] ? matched[1].toLowerCase() : '';
}

/**
 * 从文件名推导**工具名称默认值**（M-24 / T13）：默认值可改，不改变任何校验规则。
 *
 * 规则：
 * - 去掉**最后一个**扩展名（`数据清洗小工具.zip` → `数据清洗小工具`；`tool.tar.gz` → `tool.tar`）；
 * - 去后缀后为空（如 `.gitignore`）→ 回退原文件名；
 * - 超过名称上限则截断（避免"填完才报太长"）。
 */
export function nameFromFileName(fileName: string): string {
  const raw = fileName.trim();
  if (!raw) {
    return '';
  }
  const base = raw.replace(/\.[A-Za-z0-9]+$/, '').trim();
  return (base || raw).slice(0, TOOL_META_LIMITS.name);
}
