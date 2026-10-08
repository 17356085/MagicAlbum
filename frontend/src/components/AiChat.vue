<template>
  <div :class="{ 'settings-mobile-hidden': isSettingsRoute }" class="fixed bottom-4 right-4 z-50 flex flex-col items-end">
    <!-- 聊天主窗口 -->
    <div v-if="isOpen" class="mb-4 w-80 h-96 bg-white dark:bg-gray-800 rounded-lg shadow-xl border border-gray-200 dark:border-gray-700 flex flex-col overflow-hidden transition-all duration-300">
      <!-- 头部：适配模式 -->
      <div class="p-3 bg-indigo-600 dark:bg-indigo-900 text-white flex justify-between items-center shadow-sm">
        <div class="flex items-center gap-2">
          <div class="w-2 h-2 rounded-full bg-green-400 animate-pulse"></div>
          <span class="font-medium text-sm">客服小祥</span>
        </div>
        <button @click="close" class="text-indigo-100 hover:text-white dark:text-indigo-300 dark:hover:text-indigo-100">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-5 h-5">
            <path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd" />
          </svg>
        </button>
      </div>
      
      <!-- 消息列表：背景与气泡适配 -->
      <div class="flex-1 overflow-y-auto p-3 space-y-4 bg-gray-50 dark:bg-gray-900" ref="msgListRef">
        <div v-for="(msg, idx) in history" :key="idx" class="flex gap-2" :class="msg.role === 'user' ? 'flex-row-reverse' : 'flex-row'">
          <!-- 头像：仅 Assistant 显示 -->
          <div v-if="msg.role === 'assistant'" class="flex-shrink-0 w-8 h-8 rounded-full overflow-hidden border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800">
            <img 
              src="https://storage.moegirl.org.cn/moegirl/commons/f/f2/BanG_Dream%21_It%27s_MyGO%21%21%21%21%21_03091601.jpg!/fw/300/watermark/url/L21vZWdpcmwvd2F0ZXJtYXJrLnBuZw==/align/southeast/margin/10x10/opacity/50?v=20231118074914" 
              alt="小祥" 
              class="w-full h-full object-cover"
            />
          </div>
          
          <!-- 气泡 -->
          <div class="flex flex-col gap-1 max-w-[85%]" :class="msg.role === 'user' ? 'items-end' : 'items-start'">
            <span v-if="msg.role === 'assistant'" class="text-[10px] text-gray-400 dark:text-gray-500 ml-1">小祥</span>
            <div 
              class="px-3 py-2 rounded-lg text-sm shadow-sm transition-colors"
              :class="msg.role === 'user' 
                ? 'bg-indigo-600 dark:bg-indigo-700 text-white rounded-tr-none' 
                : 'bg-white dark:bg-gray-800 text-gray-800 dark:text-gray-200 rounded-tl-none border border-gray-100 dark:border-gray-700'"
            >
              {{ msg.content }}
            </div>
          </div>
        </div>
        <!-- 正在输入状态 -->
        <div v-if="isTyping" class="flex gap-2">
          <div class="flex-shrink-0 w-8 h-8 rounded-full overflow-hidden border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800">
            <img 
              src="https://storage.moegirl.org.cn/moegirl/commons/f/f2/BanG_Dream%21_It%27s_MyGO%21%21%21%21%21_03091601.jpg!/fw/300/watermark/url/L21vZWdpcmwvd2F0ZXJtYXJrLnBuZw==/align/southeast/margin/10x10/opacity/50?v=20231118074914" 
              alt="小祥" 
              class="w-full h-full object-cover"
            />
          </div>
          <div class="bg-white dark:bg-gray-800 px-3 py-2 rounded-lg rounded-tl-none text-sm text-gray-500 dark:text-gray-400 border border-gray-100 dark:border-gray-700">
            <span class="animate-pulse">...</span>
          </div>
        </div>
      </div>
      
      <!-- 输入框：背景与边框适配 -->
      <div class="p-3 bg-white dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700">
        <div class="flex gap-2">
          <input 
            v-model="inputMsg" 
            @keydown.enter.prevent="send"
            type="text" 
            placeholder="和我说说话吧..." 
            class="flex-1 text-sm rounded-md border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500 focus:border-indigo-500 dark:focus:border-indigo-400 focus:ring-indigo-500 dark:focus:ring-indigo-400"
            :disabled="isTyping"
          />
          <button 
            @click="send" 
            :disabled="!inputMsg.trim() || isTyping"
            class="px-3 py-1 bg-indigo-600 dark:bg-indigo-700 text-white rounded-md hover:bg-indigo-700 dark:hover:bg-indigo-600 disabled:opacity-50 disabled:cursor-not-allowed text-sm font-medium whitespace-nowrap transition-colors"
          >
            发送
          </button>
          <button
            v-if="isTyping"
            @click="stop"
            class="px-3 py-1 bg-gray-200 dark:bg-gray-600 text-gray-700 dark:text-gray-100 rounded-md hover:bg-gray-300 dark:hover:bg-gray-500 text-sm font-medium whitespace-nowrap"
          >
            停止
          </button>
        </div>
        <div class="mt-1 text-[10px] text-gray-400 dark:text-gray-500 text-center">
          内容由 AI 生成，仅供娱乐
        </div>
      </div>
    </div>
  
      <!-- 悬浮球按钮 -->
    <button 
      v-if="!isOpen"
      @click="isOpen = true" 
      class="group relative w-16 h-16 flex items-center justify-center transition-transform duration-300 hover:scale-110 focus:outline-none"
      title="找小祥聊天"
    >
      <!-- 外部光晕/粒子容器 -->
      <div class="absolute inset-0 rounded-full bg-cyan-500/20 blur-xl scale-75 group-hover:scale-125 transition-all duration-500 opacity-0 group-hover:opacity-100"></div>
      
      <!-- 按钮主体 SVG -->
      <svg 
        viewBox="0 0 72 72" 
        class="w-full h-full drop-shadow-2xl overflow-visible"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <!-- 夜空渐变 (Night Mode): 深邃神秘 -->
          <linearGradient id="nightSky" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stop-color="#312e81" /> <!-- indigo 900 (更亮一点) -->
            <stop offset="100%" stop-color="#1e293b" /> <!-- slate 800 -->
          </linearGradient>
          <!-- 白昼渐变 (Day Mode): 明亮清爽 -->
          <linearGradient id="daySky" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stop-color="#60a5fa" /> <!-- blue 400 -->
            <stop offset="100%" stop-color="#2563eb" /> <!-- blue 600 -->
          </linearGradient>
          
          <!-- 魔法光辉渐变 -->
          <linearGradient id="magicGlow" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stop-color="#67e8f9" /> <!-- cyan 300 -->
            <stop offset="100%" stop-color="#a5b4fc" /> <!-- indigo 300 -->
          </linearGradient>
          
          <filter id="innerGlow">
            <feGaussianBlur stdDeviation="1" result="blur"/>
            <feComposite in="SourceGraphic" in2="blur" operator="arithmetic" k2="-1" k3="1"/>
          </filter>
        </defs>

        <!-- 背景圆：根据模式切换填充 -->
        <!-- 使用 CSS 类控制 fill 属性在 dark/light 模式下的引用 -->
        <circle 
          cx="36" cy="36" r="32" 
          class="fill-[url(#daySky)] dark:fill-[url(#nightSky)] transition-colors duration-500 shadow-inner" 
          stroke="currentColor" 
          stroke-width="1"
          stroke-opacity="0.3" 
        />
        
        <!-- 装饰环：魔法阵外圈 -->
        <g class="origin-center transition-transform duration-[10s] ease-linear group-hover:animate-spin-slow opacity-80 group-hover:opacity-100">
          <circle cx="36" cy="36" r="26" fill="none" stroke="url(#magicGlow)" stroke-width="1.5" stroke-dasharray="4 6" />
          <path d="M36 10 L36 14 M36 58 L36 62 M10 36 L14 36 M58 36 L62 36" stroke="#cffafe" stroke-width="2" stroke-linecap="round" />
        </g>

        <!-- 核心图标：抽象知更鸟/星型符文 -->
        <g class="origin-center transition-all duration-300 group-hover:scale-110">
          <!-- 翅膀/星芒 -->
          <path 
            d="M36 20 C36 20 42 30 54 32 C42 38 36 52 36 52 C36 52 30 38 18 32 C30 30 36 20 36 20 Z" 
            fill="#ffffff" 
            fill-opacity="0.95"
            filter="url(#innerGlow)"
            class="drop-shadow-md"
          />
          <!-- 中心核心 -->
          <circle cx="36" cy="36" r="4" fill="#38bdf8" class="animate-pulse" />
        </g>

        <!-- 消息红点 -->
        <circle cx="56" cy="16" r="6" fill="#fb7185" stroke="#ffffff" stroke-width="2" class="animate-bounce" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
/* Settings controls stay reachable on phones and tablets; chat remains available elsewhere. */
@media (max-width: 1023px) {
  .settings-mobile-hidden {
    display: none !important;
  }
}

/* 定义缓慢旋转动画 */
@keyframes spin-slow {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.animate-spin-slow {
  animation: spin-slow 8s linear infinite;
}
</style>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { createChatStream } from '@/api/ai'
import type { AiChatMessage, ChatStreamHandle } from '@/api/ai'

const route = useRoute()
const isSettingsRoute = computed(() => route.path === '/settings' || route.path.startsWith('/settings/'))
const isOpen = ref(false)
const inputMsg = ref('')
const isTyping = ref(false)
const msgListRef = ref<HTMLElement | null>(null)
const streamHandle = ref<ChatStreamHandle | null>(null)

const history = ref<AiChatMessage[]>([
  { role: 'assistant', content: '……你是谁？如果是无关紧要的事，请不要打扰我。' }
])

function scrollToBottom(): void {
  nextTick(() => {
    if (msgListRef.value) {
      msgListRef.value.scrollTop = msgListRef.value.scrollHeight
    }
  })
}

// 监听窗口打开，自动滚动到底部
watch(isOpen, (val) => {
  if (val) scrollToBottom()
})

function stop(): void {
  const handle = streamHandle.value
  streamHandle.value = null
  handle?.cancel()
  isTyping.value = false
}

function close(): void {
  stop()
  isOpen.value = false
}

function send(): void {
  const content = inputMsg.value.trim()
  if (!content || isTyping.value) return

  // 添加用户消息
  history.value.push({ role: 'user', content })
  inputMsg.value = ''
  isTyping.value = true
  scrollToBottom()

  const contextMessages = history.value.slice(-10)

  let currentReply = ''
  let replyIndex = -1

  const handle = createChatStream(
    contextMessages,
    (data) => {
      const payload = (data ?? {}) as Partial<AiChatMessage>
      // 收到第一个 chunk 时，初始化 assistant 消息
      if (replyIndex === -1) {
        history.value.push({ role: 'assistant', content: '' })
        replyIndex = history.value.length - 1
        // Keep the stream controls visible until the provider closes or is cancelled.
      }
      currentReply += (payload.content || '')
      history.value[replyIndex].content = currentReply
      scrollToBottom()
    },
    () => {
      if (streamHandle.value !== handle) return
      isTyping.value = false
      if (replyIndex === -1 || !history.value[replyIndex]?.content) {
        history.value.push({ role: 'assistant', content: '呜呜，连接出错了，请稍后再试...' })
        scrollToBottom()
      }
    },
    () => {
      if (streamHandle.value === handle) {
        isTyping.value = false
      }
    }
  )
  streamHandle.value = handle
  void handle.done.finally(() => {
    if (streamHandle.value === handle) {
      streamHandle.value = null
      isTyping.value = false
    }
  })
}

onBeforeUnmount(stop)
</script>
