import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

const root = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig(({ mode }) => {
  // 只读 WENGU_ 开头的变量：后端地址只给开发服务器用，不打包进前端代码
  const env = loadEnv(mode, root, 'WENGU_')

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      // 开发时由 Vite 转发接口请求：页面和接口同源，Cookie 照常带上，不需要后端开 CORS
      proxy: {
        '/api': env.WENGU_API_TARGET || 'http://localhost:8080',
      },
    },
  }
})
