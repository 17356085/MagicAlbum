// import { fileURLToPath, URL } from 'node:url'

// import { defineConfig } from 'vite'
// import vue from '@vitejs/plugin-vue'

// // https://vite.dev/config/
// export default defineConfig({
//   plugins: [
//     vue(),
//   ],
//   resolve: {
//     alias: {
//       '@': fileURLToPath(new URL('./src', import.meta.url)),
//     },
//   },
//   server: {
//     host: '127.0.0.1',
//     port: 5173,
//   },
//   build: {
//     rollupOptions: {
//       output: {
//         manualChunks(id) {
//           if (!id.includes('node_modules')) return

//           if (id.includes('markdown-it') || id.includes('markdown-it-katex') || id.includes('katex')) return 'markdown'
//           if (id.includes('highlight.js')) return 'highlight'
//           if (id.includes('vue') || id.includes('vue-router') || id.includes('pinia')) return 'vue-vendor'
//           if (id.includes('axios') || id.includes('dompurify')) return 'app-vendor'
//         },
//       },
//     },
//     chunkSizeWarningLimit: 700,
//   },
// })
import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ command, mode }) => {
  if (command === 'build' && loadEnv(mode, process.cwd(), '').VITE_USE_API_MOCK === 'true') {
    throw new Error('VITE_USE_API_MOCK=true is only for local development and cannot be included in a build')
  }

  return {
  plugins: [
    vue(),
  ],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    host: '127.0.0.1',
    port: 5173,

    proxy: {
      '/api/v1': {
        // target 不要带 /api/v1 路径后缀，否则请求路径会被拼成 /api/v1/api/v1/...
        target: 'http://127.0.0.1:9000',
        changeOrigin: true
      },
      '/uploads': {
        // 后端返回的本地图片地址是 /uploads/...，由网关统一转发到后端静态资源。
        target: 'http://127.0.0.1:9000',
        changeOrigin: true
      },
    },
  },

  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return

          if (
            id.includes('markdown-it') ||
            id.includes('markdown-it-katex') ||
            id.includes('katex')
          ) {
            return 'markdown'
          }

          if (id.includes('highlight.js')) {
            return 'highlight'
          }

          if (
            id.includes('vue') ||
            id.includes('vue-router') ||
            id.includes('pinia')
          ) {
            return 'vue-vendor'
          }

          if (
            id.includes('axios') ||
            id.includes('dompurify')
          ) {
            return 'app-vendor'
          }
        },
      },
    },

    chunkSizeWarningLimit: 700,
  },
  }
})
