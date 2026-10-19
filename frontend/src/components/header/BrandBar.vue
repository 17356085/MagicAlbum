<template>
  <div class="flex min-w-0 items-center gap-1 sm:gap-2 lg:gap-3">
    <router-link to="/discover" class="inline-flex min-h-12 shrink-0 items-center gap-2 hover:opacity-90 lg:min-h-0" aria-label="返回发现">
      <IconSite />
      <span class="min-w-0 max-w-[4rem] truncate text-base font-bold tracking-tight text-gray-800 dark:text-gray-100 lg:max-w-none lg:text-lg">{{ SITE_NAME }}</span>
    </router-link>
    <span class="hidden shrink-0 rounded bg-brandDay-50 px-1.5 py-0.5 text-[10px] font-medium text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-200 xl:inline">Beta</span>
  </div>
</template>

<script setup lang="ts">
import { SITE_NAME } from '@/config/brand'
import IconSite from '@/components/icons/IconSite.vue'
</script>
