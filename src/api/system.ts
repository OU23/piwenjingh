import { get, post } from './request';

/** 系统初始化（docs/接口契约.md §十一） */
const SYSTEM_API = {
  initStatus: '/api/system/init-status',
  init: '/api/system/init',
} as const;

export interface InitStatusVO {
  initialized: boolean;
}

export interface InitDTO {
  adminUsername: string;
  adminPassword: string;
  adminRealName: string;
}

/** 查询系统是否已初始化（公开接口） */
export function getInitStatus(): Promise<InitStatusVO> {
  return get<InitStatusVO>(SYSTEM_API.initStatus);
}

/** 一键初始化（公开接口，仅未初始化时可用） */
export function initSystem(data: InitDTO): Promise<null> {
  return post<null>(SYSTEM_API.init, data);
}
