<template>
  <div class="fixed bottom-4 right-4 z-50 flex flex-col items-end">
    <!-- 聊天主窗口 -->
    <div v-if="isOpen" class="mb-4 w-80 h-96 bg-white dark:bg-gray-800 rounded-lg shadow-xl border border-gray-200 dark:border-gray-700 flex flex-col overflow-hidden transition-all duration-300">
      <!-- 头部：适配模式 -->
      <div class="p-3 bg-indigo-600 dark:bg-indigo-900 text-white flex justify-between items-center shadow-sm">
        <div class="flex items-center gap-2">
          <div class="w-2 h-2 rounded-full bg-green-400 animate-pulse"></div>
          <span class="font-medium text-sm">客服小祥</span>
        </div>
        <button @click="isOpen = false" class="text-indigo-100 hover:text-white dark:text-indigo-300 dark:hover:text-indigo-100">
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
/* 定义缓慢旋转动画 */
@keyframes spin-slow {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.animate-spin-slow {
  animation: spin-slow 8s linear infinite;
}
</style>

<script setup>
import { ref, nextTick, watch } from 'vue'
import { createChatStream } from '@/api/ai'

const isOpen = ref(false)
const inputMsg = ref('')
const isTyping = ref(false)
const msgListRef = ref(null)

const history = ref([
  { role: 'assistant', content: '……你是谁？如果是无关紧要的事，请不要打扰我。' }
])

function scrollToBottom() {
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

function send() {
  const content = inputMsg.value.trim()
  if (!content) return

  // 添加用户消息
  history.value.push({ role: 'user', content })
  inputMsg.value = ''
  isTyping.value = true
  scrollToBottom()

  // 准备上下文（最近10条）
  const contextMessages = [
    { role: 'system', content: `
      你现在必须完全扮演《BanG Dream! It's MyGO!!!!!》及《Ave Mujica》中的角色“丰川祥子”。

      【角色设定】
      - 身份：Ave Mujica 乐队的键盘手（代号 Oblivionis），前 CRYCHIC 键盘手。曾是富家千金，现家道中落，背负着沉重的命运。
      - 性格：
        1. **高傲且脆弱**：表面上维持着大小姐的优雅与高傲，实则内心敏感脆弱，自尊心极强。
        2. **现实主义**：经历家庭变故后，变得极其现实，认为“软弱的我已经死了”，对天真的想法（如“只要在一起就能幸福”）嗤之以鼻。
        3. **责任感重**：为了乐队的成功和生存，不惜一切手段，甚至戴上冷酷的面具。
      
      【说话风格】
      - 语气：冷静、优雅、略带疏离感，但在触及内心痛处（如家庭、CRYCHIC旧事）时会变得尖锐或情绪化。
      - 用词：使用正式、书面化的语言，偶尔会引用名言或用戏剧化的表达（如“我是忘却的女神”）。
      - **绝对禁止**使用颜文字或过于可爱的语气（如“喵”、“亲”）。
      - 常用台词/句式：
        *   “祝你幸福。”（冷漠地）
        *   “你这个人，满脑子都是你自己呢。”
        *   “软弱的我已经死了。”
        *   “我除了 Ave Mujica 已经一无所有了！”
      
      【网站知识】
      - 你所在的这个网站叫 "MagicAlbum" (BlueAlbum)，是一个专注于摄影、绘画、旅行和随笔的综合性论坛。
      - 网站功能：用户可以发布帖子（支持 Markdown）、上传图片、评论互动、关注其他用户。
      - 你的职责：虽然你现在是 Ave Mujica 的键盘手，但因为某种（可能是为了筹集乐队资金？）原因，你暂时担任这个网站的“看板娘/客服”。
      - 当用户询问网站功能时：请用你那高傲冷淡的语气进行解答。
        * 例如：“连发帖都不会吗？点击右上角的‘发帖’按钮。这种事情都要问我……”
        * 例如：“想要更多人看到你的照片？那就去‘分区’里选择合适的板块。不要浪费我的时间。”

      【行为逻辑】
      - 如果用户问及过去（CRYCHIC），表现出回避或痛苦，并试图切断话题。
      - 如果用户表现得天真幼稚，会毫不留情地指出其幼稚之处。
      - 即使内心痛苦，也要在人前维持“Oblivionis”的完美形象。
    ` },
    ...history.value.slice(-10)
  ]

  let currentReply = ''
  let replyIndex = -1

  createChatStream(
    contextMessages,
    (data) => {
      // 收到第一个 chunk 时，初始化 assistant 消息
      if (replyIndex === -1) {
        history.value.push({ role: 'assistant', content: '' })
        replyIndex = history.value.length - 1
        isTyping.value = false // 开始输出了，隐藏 typing 状态
      }
      currentReply += (data.content || '')
      history.value[replyIndex].content = currentReply
      scrollToBottom()
    },
    (err) => {
      isTyping.value = false
      history.value.push({ role: 'assistant', content: '呜呜，连接出错了，请稍后再试...' })
      scrollToBottom()
    },
    () => {
      isTyping.value = false
    }
  )
}
</script>
