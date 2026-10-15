import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { sourceAliases } from './config/paths'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: sourceAliases,
  },
  test: {
    environment: 'jsdom',
  },
})
