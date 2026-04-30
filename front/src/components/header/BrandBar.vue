<template>
  <div class="flex items-center gap-3">
    <router-link to="/discover" class="inline-flex items-center hover:opacity-90" aria-label="返回发现">
      <IconMagicalbum aria-label="Magicalbum Logo" />
    </router-link>
    <router-link to="/discover" class="text-lg font-semibold tracking-wide hover:opacity-90 text-gray-800 dark:text-gray-100">MagicAlbum</router-link>
    <span class="ml-2 rounded bg-orange-100 px-2 py-0.5 text-xs text-orange-600">beta</span>
  </div>
</template>

<script setup lang="ts">
import IconMagicalbum from '@/components/icons/IconMagicalbum.vue'
</script>
