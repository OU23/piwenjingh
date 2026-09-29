import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 端口与代理目标取自 .env.*（基线见 docs/技术选型.md §3.3，接口基址约定见 docs/接口契约.md §1.1）
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: Number(env.VITE_DEV_PORT || 5173),
      // 开发期把 /api 与 /ws 转发到后端，前端无需处理跨域；基址仍可用 VITE_API_BASE_URL 覆盖为绝对地址
      proxy: {
        '/api': {
          target: env.VITE_PROXY_TARGET,
          changeOrigin: true,
        },
        // M-18（P7）：WebSocket 实时通道 —— 必须开启 ws 代理，
        // 否则握手会打到 dev server 而非后端，连接直接失败。
        '/ws': {
          target: env.VITE_PROXY_TARGET,
          changeOrigin: true,
          ws: true,
        },
      },
    },
  }
})
