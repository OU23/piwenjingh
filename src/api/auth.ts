import { get, post } from './request';
import type { ID } from './types';

/** 认证（docs/接口契约.md §三） */
const AUTH_API = {
  login: '/api/auth/login',
  logout: '/api/auth/logout',
  me: '/api/auth/me',
} as const;

export interface LoginDTO {
  username: string;
  password: string;
  /**
   * v1.8 新增（契约 §3.1）：强制登录。
   * `true` = 该账号已有活跃会话时**作废旧会话**并完成本次登录；缺省 `false`。
   */
  force?: boolean;
}

export interface CurrentUserVO {
  userId: ID;
  username: string;
  realName: string;
  deptId: ID;
  deptName: string;
  /** 权限点编码列表（契约 §13.1，前后端共用同一套） */
  permissions: string[];
}

export interface LoginVO {
  token: string;
  expiresIn: number;
  user: CurrentUserVO;
}

/** 登录：成功返回令牌与用户信息（含权限点） */
export function login(data: LoginDTO): Promise<LoginVO> {
  return post<LoginVO>(AUTH_API.login, data);
}

/** 登出 */
export function logout(): Promise<null> {
  return post<null>(AUTH_API.logout);
}

/** 当前登录用户信息 */
export function getCurrentUser(): Promise<CurrentUserVO> {
  return get<CurrentUserVO>(AUTH_API.me);
}
