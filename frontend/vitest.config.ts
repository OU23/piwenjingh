import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 测试目录分层见 docs/rules/02-规范细则/test/testing-rules.md §8 / §19.1、§19.3
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.ts'],
    globals: false,
    restoreMocks: true,
    // 全量并发运行时，首次按需加载页面组件（含 Element Plus）会占用单测执行时间，
    // 默认 5s 门槛偏紧；此处放宽到 20s，避免把"转换耗时"误判成用例失败。
    testTimeout: 20000,
  },
})
