import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return

          if (id.includes('markdown-it') || id.includes('markdown-it-katex') || id.includes('katex')) return 'markdown'
          if (id.includes('highlight.js')) return 'highlight'
          if (id.includes('vue') || id.includes('vue-router') || id.includes('pinia')) return 'vue-vendor'
          if (id.includes('axios') || id.includes('dompurify')) return 'app-vendor'
        },
      },
    },
    chunkSizeWarningLimit: 700,
  },
})
