<script setup>
import HeaderBangumi from '@/components/HeaderBangumi.vue'
import SidebarLeft from '@/components/SidebarLeft.vue'
</script>

<template>
  <div class="min-h-screen bg-gray-50 dark:bg-gray-900 dark:text-gray-100">
    <HeaderBangumi />

    <main class="mx-auto max-w-6xl px-4 py-6 grid grid-cols-1 lg:grid-cols-12 gap-6">
      <div class="hidden lg:block lg:col-span-3">
        <SidebarLeft />
      </div>
      <div class="lg:col-span-9">
        <div class="space-y-4">
          <router-view />
        </div>
      </div>
    </main>

    <footer class="border-t border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="mx-auto max-w-6xl px-4 py-6 text-xs text-gray-500 dark:text-gray-400">© 2025 MagicAlbum · 设计参考 bangumi.tv</div>
    </footer>
  </div>
</template>

<style scoped>
</style>
