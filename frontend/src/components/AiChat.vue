<template>
  <div :class="{ 'settings-mobile-hidden': isSettingsRoute, 'chat-open': isOpen }" class="chat-widget fixed bottom-4 right-4 flex flex-col items-end">
    <!-- 聊天主窗口 -->
    <div v-if="isOpen" class="chat-window ui-panel mb-4 flex flex-col overflow-hidden transition-all duration-300" role="dialog" aria-label="小祥聊天" @keydown.esc.stop.prevent="close">
      <!-- 头部：适配模式 -->
      <div class="p-3 bg-indigo-600 dark:bg-indigo-900 text-white flex justify-between items-center shadow-sm">
        <div class="flex items-center gap-2">
          <div class="w-2 h-2 rounded-full bg-green-400 animate-pulse"></div>
          <span class="font-medium text-sm">客服小祥</span>
        </div>
        <button @click="close" class="inline-flex min-h-11 min-w-11 items-center justify-center text-indigo-100 hover:text-white dark:text-indigo-300 dark:hover:text-indigo-100" aria-label="关闭聊天">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-5 h-5">
            <path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd" />
          </svg>
        </button>
      </div>
      
      <!-- 消息列表：背景与气泡适配 -->
      <div class="flex-1 ui-scroll overflow-y-auto p-3 space-y-4 bg-gray-50 dark:bg-gray-900" ref="msgListRef">
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
            ref="chatInputRef"
            v-model="inputMsg" 
            @keydown.enter.prevent="send"
            type="text" 
            placeholder="和我说说话吧..." 
            class="ui-field flex-1 text-sm" aria-label="聊天消息"
            :disabled="isTyping"
          />
          <button 
            @click="send" 
            :disabled="!inputMsg.trim() || isTyping"
            class="ui-button-primary disabled:opacity-50 whitespace-nowrap"
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
  
    <button v-if="!isOpen" type="button" @click="isOpen = true" class="chat-launcher flex h-12 w-12 items-center justify-center rounded-xl" title="找小祥聊天" aria-label="找小祥聊天">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" class="h-6 w-6" aria-hidden="true"><path d="M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-5 3v-3a2 2 0 0 1-2-2V6a2 2 0 0 1 3-2Z" stroke-linejoin="round"/><path d="M7 9h10M7 13h6" stroke-linecap="round"/></svg>
    </button>
  </div>
</template>

<style scoped>
.chat-widget { z-index: var(--ui-z-floating); }
.chat-window { width: min(360px, calc(100vw - 32px)); height: min(480px, calc(100dvh - 144px)); }
.chat-launcher { color: var(--ui-accent); background: var(--ui-surface); border: 1px solid var(--ui-border); }
.chat-launcher:hover { background: var(--ui-accent-soft); }
@media (max-width: 1023px) {
  .chat-launcher { display: none; }
  .chat-widget.chat-open { inset-inline: 12px; bottom: calc(12px + var(--mobile-viewport-bottom, 0px)); }
  .chat-window { width: 100%; margin-bottom: 0; height: min(560px, calc(var(--mobile-viewport-height, 100dvh) - 88px)); }
}

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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
const chatInputRef = ref<HTMLInputElement | null>(null)
let opener: HTMLElement | null = null

function openFromAccount(): void {
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  isOpen.value = true
  void nextTick(() => chatInputRef.value?.focus())
}

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
  if (opener?.isConnected) opener.focus()
  opener = null
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

onMounted(() => window.addEventListener('open-ai-chat', openFromAccount))
onBeforeUnmount(() => {
  window.removeEventListener('open-ai-chat', openFromAccount)
  stop()
})
</script>
