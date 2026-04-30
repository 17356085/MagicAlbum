<template>
  <button
    class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700 inline-flex items-center"
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
    <span class="sr-only">登录</span>
  </button>
</template>

<script setup lang="ts">
defineEmits<{
  'open-login': []
}>()
</script>
