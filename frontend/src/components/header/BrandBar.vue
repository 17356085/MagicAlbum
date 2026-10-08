<template>
  <div class="flex min-w-0 items-center gap-2 md:gap-3">
    <router-link to="/discover" class="inline-flex shrink-0 items-center hover:opacity-90" aria-label="返回发现">
      <IconMagicalbum aria-label="Magicalbum Logo" />
    </router-link>
    <router-link to="/discover" class="hidden truncate text-lg font-semibold tracking-wide text-gray-800 hover:opacity-90 dark:text-gray-100 md:inline">MagicAlbum</router-link>
    <span class="hidden shrink-0 rounded bg-orange-100 px-2 py-0.5 text-xs text-orange-600 md:inline">beta</span>
  </div>
</template>

<script setup lang="ts">
import IconMagicalbum from '@/components/icons/IconMagicalbum.vue'
</script>
