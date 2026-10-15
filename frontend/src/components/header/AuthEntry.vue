<template>
  <button
    class="inline-flex h-10 items-center gap-2 rounded-lg px-2 hover:bg-gray-100 dark:hover:bg-gray-700 md:px-3"
    aria-label="登录"
    title="登录"
    @click="$emit('open-login')"
  >
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
      <circle cx="12" cy="8" r="3.25" stroke-width="1.8" />
      <path d="M5 19.25C5.9 16.55 8.43 15 12 15s6.1 1.55 7 4.25" stroke-width="1.8" stroke-linecap="round" />
      <path d="M19 8.75h3" stroke-width="1.8" stroke-linecap="round" />
      <path d="M20.5 7.25v3" stroke-width="1.8" stroke-linecap="round" />
    </svg>
    <span class="hidden text-sm font-medium md:inline">登录</span>
  </button>
</template>

<script setup lang="ts">
defineEmits<{
  'open-login': []
}>()
</script>
