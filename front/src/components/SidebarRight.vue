<template>
  <aside class="space-y-3">
    <!-- 专属客服卡片 -->
    <div class="rounded-md border border-indigo-100 bg-indigo-50 dark:bg-indigo-900/20 dark:border-indigo-800 p-3">
      <div class="flex items-center gap-2 mb-2">
        <span class="relative flex h-3 w-3">
          <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-indigo-400 opacity-75"></span>
          <span class="relative inline-flex rounded-full h-3 w-3 bg-indigo-500"></span>
        </span>
        <span class="text-sm font-bold text-indigo-700 dark:text-indigo-300">专属客服</span>
      </div>
      <p class="text-xs text-indigo-600 dark:text-indigo-400 mb-2">有问题？小祥随时为您解答！</p>
      <button 
        @click="openChat"
        class="w-full py-1.5 text-xs font-medium text-white bg-indigo-600 hover:bg-indigo-700 rounded transition-colors"
      >
        找小祥聊聊
      </button>
    </div>

    <div class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">热门话题</div>
      <ul class="p-2 text-sm">
        <li class="flex justify-between gap-2 py-1">
          <a href="#" class="truncate hover:underline">近期摄影打卡</a>
          <span class="text-xs text-gray-500 dark:text-gray-400">32</span>
        </li>
        <li class="flex justify-between gap-2 py-1">
          <a href="#" class="truncate hover:underline">新手入门镜头推荐</a>
          <span class="text-xs text-gray-500 dark:text-gray-400">18</span>
        </li>
        <li class="flex justify-between gap-2 py-1">
          <a href="#" class="truncate hover:underline">旅行照片后期流程</a>
          <span class="text-xs text-gray-500 dark:text-gray-400">12</span>
        </li>
      </ul>
    </div>
    <div class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">公告</div>
      <div class="p-3 text-xs text-gray-700 dark:text-gray-200">欢迎体验 MagicAlbum beta，功能持续完善中。</div>
    </div>
  </aside>
</template>

<script setup>
import { inject } from 'vue'

// 假设 App.vue 或 Layout 中 provide 了打开聊天的方法
// 或者这里触发全局事件。简单起见，这里假设我们通过 window 自定义事件或者状态管理来通信
// 但为了更优雅，我们通常在 App.vue 引入 AiChat 组件，并通过 Provide/Inject 控制
// 这里为了不修改 App.vue 增加复杂性，我们使用一个简单的 EventBus 替代方案，或者
// 直接修改 SidebarRight 引入 AiChat (但这会导致 AiChat 嵌套在 Sidebar 中，可能被遮挡)
// 最佳实践是将 AiChat 放在 App.vue 根部。

// 修正方案：将 AiChat 放在 App.vue，这里只负责触发打开。
// 我们可以派发一个自定义事件 'open-ai-chat'
function openChat() {
  window.dispatchEvent(new CustomEvent('open-ai-chat'))
}
</script>