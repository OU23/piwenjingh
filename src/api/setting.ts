import { get, put } from './request';

/** 全局设置（docs/接口契约.md §十） */
const SETTING_API = {
  base: '/api/settings',
} as const;

export interface SettingVO {
  /** 工具文件存储根路径（绝对路径）；**无 sys:setting 权限时为 null**（补充说明 N-03） */
  storageRoot: string | null;
  /** 单文件大小上限（字节），默认 10 GB */
  maxFileSize: number;
}

/** 保存时只提交变更项 */
export interface SettingSaveDTO {
  storageRoot?: string;
  maxFileSize?: number;
}

/** 读取全局设置（登录即可；上传页需读取单文件上限） */
export function getSettings(): Promise<SettingVO> {
  return get<SettingVO>(SETTING_API.base);
}

/** 保存全局设置（需系统设置权限） */
export function saveSettings(data: SettingSaveDTO): Promise<null> {
  return put<null>(SETTING_API.base, data);
}
