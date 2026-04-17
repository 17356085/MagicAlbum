<template>
  <div class="border-t border-gray-100 px-4 py-2.5 dark:border-gray-800">
    <div class="flex items-center gap-3 text-[11px] uppercase tracking-[0.22em] text-gray-400 dark:text-gray-500">
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
      <span>第三方登录</span>
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
    </div>
    <div class="mt-2.5 flex items-center justify-center gap-2.5">
      <button
        v-for="provider in providers"
        :key="provider.id"
        type="button"
        class="inline-flex h-9 w-9 items-center justify-center rounded-full border bg-white shadow-sm transition hover:-translate-y-0.5 dark:bg-gray-800"
        :class="provider.enabled ? 'border-gray-200 text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-700' : 'border-gray-200 text-gray-400 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-500 dark:hover:bg-gray-700'"
        :aria-label="`${provider.label} 登录`"
        :title="provider.enabled ? `${provider.label} 登录` : `${provider.label} 登录准备中`"
        @click="emit('select', provider.id)"
      >
        <svg v-if="provider.id === 'github'" viewBox="0 0 24 24" class="h-5 w-5 fill-current" aria-hidden="true">
          <path d="M12 2C6.48 2 2 6.58 2 12.22c0 4.5 2.87 8.32 6.84 9.67.5.1.68-.22.68-.5 0-.24-.01-1.04-.01-1.88-2.78.62-3.37-1.22-3.37-1.22-.46-1.2-1.11-1.52-1.11-1.52-.91-.64.07-.63.07-.63 1 .07 1.53 1.06 1.53 1.06.9 1.57 2.36 1.12 2.94.86.09-.67.35-1.12.63-1.38-2.22-.26-4.56-1.15-4.56-5.12 0-1.13.39-2.05 1.03-2.77-.1-.26-.45-1.3.1-2.72 0 0 .84-.27 2.75 1.06A9.3 9.3 0 0 1 12 6.84c.85 0 1.71.12 2.51.36 1.9-1.33 2.74-1.06 2.74-1.06.56 1.42.21 2.46.1 2.72.64.72 1.03 1.64 1.03 2.77 0 3.98-2.34 4.86-4.57 5.11.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.8 0 .28.18.61.69.5A10.25 10.25 0 0 0 22 12.22C22 6.58 17.52 2 12 2z"/>
        </svg>

        <svg v-else-if="provider.id === 'google'" viewBox="0 0 24 24" class="h-5 w-5" aria-hidden="true">
          <path fill="#EA4335" d="M12 10.2v3.9h5.42c-.24 1.26-.95 2.33-2.02 3.05l3.27 2.59c1.91-1.8 3.01-4.45 3.01-7.61 0-.72-.06-1.42-.18-2.1H12z"/>
          <path fill="#34A853" d="M12 22c2.73 0 5.02-.92 6.69-2.49l-3.27-2.59c-.91.62-2.07.99-3.42.99-2.63 0-4.86-1.82-5.66-4.27H2.96v2.67A9.99 9.99 0 0 0 12 22z"/>
          <path fill="#4A90E2" d="M6.34 13.64A6.03 6.03 0 0 1 6.02 12c0-.57.11-1.13.31-1.64V7.69H2.96A10.2 10.2 0 0 0 2 12c0 1.56.35 3.03.96 4.31l3.38-2.67z"/>
          <path fill="#FBBC05" d="M12 6.09c1.48 0 2.81.52 3.86 1.53l2.89-2.96C17.01 2.97 14.73 2 12 2A9.99 9.99 0 0 0 2.96 7.69l3.38 2.67C7.14 7.91 9.37 6.09 12 6.09z"/>
        </svg>

        <svg v-else-if="provider.id === 'apple'" viewBox="0 0 24 24" class="h-5 w-5 fill-current" aria-hidden="true">
          <path d="M16.37 12.21c.02 2.38 2.08 3.17 2.1 3.18-.02.06-.33 1.16-1.09 2.31-.66.99-1.34 1.97-2.42 1.99-1.05.02-1.39-.63-2.59-.63s-1.57.61-2.56.65c-1.03.04-1.81-1.05-2.47-2.03-1.35-1.98-2.38-5.61-.99-8.05.69-1.21 1.92-1.98 3.26-2 1.02-.02 1.99.7 2.59.7.6 0 1.74-.86 2.94-.73.5.02 1.89.2 2.79 1.54-.07.05-1.66.99-1.64 3.07zM14.55 5.2c.55-.68.92-1.62.82-2.56-.79.03-1.75.54-2.32 1.21-.51.59-.96 1.54-.84 2.45.88.07 1.79-.46 2.34-1.1z"/>
        </svg>

        <svg v-else viewBox="0 0 24 24" class="h-5 w-5" aria-hidden="true">
          <path fill="#07C160" d="M8.06 8.03c-2.5 0-4.53 1.65-4.53 3.7 0 1.19.69 2.24 1.77 2.92l-.45 1.68 1.96-1.02c.4.08.82.12 1.25.12 2.5 0 4.52-1.65 4.52-3.7s-2.02-3.7-4.52-3.7zm-1.7 2.95a.55.55 0 1 1 0-1.1.55.55 0 0 1 0 1.1zm3.4 0a.55.55 0 1 1 0-1.1.55.55 0 0 1 0 1.1z"/>
          <path fill="#07C160" d="M16.3 9.18c-2.82 0-5.1 1.86-5.1 4.15 0 2.29 2.28 4.15 5.1 4.15.47 0 .93-.05 1.37-.15l2.14 1.12-.5-1.88c1.17-.76 1.9-1.93 1.9-3.24 0-2.3-2.29-4.15-5.1-4.15zm-1.91 3.31a.62.62 0 1 1 0-1.24.62.62 0 0 1 0 1.24zm3.82 0a.62.62 0 1 1 0-1.24.62.62 0 0 1 0 1.24z"/>
        </svg>
      </button>
    </div>
    <p class="mt-2 text-center text-[10px] leading-4 text-gray-400 dark:text-gray-500">
      {{ footerText }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { getOAuthProviderConfigs } from '@/services/thirdPartyAuth'
import type { OAuthProvider } from '@/types'

const emit = defineEmits<{
  select: [provider: OAuthProvider]
}>()

const providers = getOAuthProviderConfigs()

const footerText = computed(() => {
  const readyProviders = providers.filter((provider) => provider.enabled)
  if (!readyProviders.length) return 'OAuth 入口准备中'
  if (readyProviders.length === 1) return `${readyProviders[0].label} 授权入口已预留`
  return `${readyProviders.map((provider) => provider.label).join(' / ')} 授权入口已预留`
})
</script>
