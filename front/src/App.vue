<script setup>
import HeaderBangumi from '@/components/HeaderBangumi.vue'
import SidebarLeft from '@/components/SidebarLeft.vue'
</script>

<template>
  <div class="min-h-screen flex flex-col bg-gray-50 dark:bg-gray-900 dark:text-gray-100 pt-16">
    <HeaderBangumi />

    <main class="mx-auto max-w-7xl px-4 py-6 grid grid-cols-1 lg:grid-cols-12 gap-6 items-start flex-1 w-full">
      <div class="hidden lg:block lg:col-span-3">
        <SidebarLeft />
      </div>
      <div class="lg:col-span-9">
        <div class="space-y-4">
          <router-view />
        </div>
      </div>
    </main>

    <footer class="mt-auto border-t border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700 w-full">
      <div class="mx-auto max-w-7xl px-4 py-6 text-xs text-gray-500 dark:text-gray-400">© 2025 MagicAlbum · 设计参考 bangumi.tv</div>
    </footer>
  </div>
</template>

<style scoped>
</style>
