/// <reference types="vite/client" />

// 前端可配置项（值来自 .env.*；不含任何密钥）
interface ImportMetaEnv {
  /** 接口基址；留空 = 同源（生产由 nginx 反代 /api） */
  readonly VITE_API_BASE_URL: string;
}
