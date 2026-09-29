/// <reference types="vite/client" />

// 前端可配置项（值来自 .env.*；不含任何密钥）
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  /**
   * 方案 2-B 开关：非安全上下文（HTTP / IP）下的原生下载。
   * 取值 `'on'` 时启用（需后端已提供 `POST /api/tools/{id}/download-token` 且下载接口支持 `?token=`）；
   * 留空 / 其它值 = 关闭，走整包读入降级路径。详见 docs/内部-大文件下载方案.md。
   */
  readonly VITE_NATIVE_DOWNLOAD?: string;
}
