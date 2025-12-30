<script setup>
import { ref, onMounted } from 'vue'
import { listSections } from '@/api/sections'

const loading = ref(false)
const error = ref('')
const sections = ref([])

const themeMap = {
  anime: {
    classes: 'from-fuchsia-50 to-fuchsia-100 border-fuchsia-200 text-fuchsia-900 group-hover:border-fuchsia-300 dark:from-fuchsia-900/20 dark:to-fuchsia-800/20 dark:border-fuchsia-700/50 dark:text-fuchsia-100',
    icon: '🌸'
  },
  music: {
    classes: 'from-violet-50 to-violet-100 border-violet-200 text-violet-900 group-hover:border-violet-300 dark:from-violet-900/20 dark:to-violet-800/20 dark:border-violet-700/50 dark:text-violet-100',
    icon: '🎵'
  },
  game: {
    classes: 'from-indigo-50 to-indigo-100 border-indigo-200 text-indigo-900 group-hover:border-indigo-300 dark:from-indigo-900/20 dark:to-indigo-800/20 dark:border-indigo-700/50 dark:text-indigo-100',
    icon: '🎮'
  },
  f1: {
    classes: 'from-blue-50 to-blue-100 border-blue-200 text-blue-900 group-hover:border-blue-300 dark:from-blue-900/20 dark:to-blue-800/20 dark:border-blue-700/50 dark:text-blue-100',
    icon: '🏎️'
  },
  tech: {
    classes: 'from-sky-50 to-sky-100 border-sky-200 text-sky-900 group-hover:border-sky-300 dark:from-sky-900/20 dark:to-sky-800/20 dark:border-sky-700/50 dark:text-sky-100',
    icon: '💻'
  },
  coding: {
    classes: 'from-cyan-50 to-cyan-100 border-cyan-200 text-cyan-900 group-hover:border-cyan-300 dark:from-cyan-900/20 dark:to-cyan-800/20 dark:border-cyan-700/50 dark:text-cyan-100',
    icon: '⌨️'
  },
  food: {
    classes: 'from-slate-50 to-slate-100 border-slate-200 text-slate-900 group-hover:border-slate-300 dark:from-slate-800/40 dark:to-slate-700/40 dark:border-slate-600/50 dark:text-slate-200',
    icon: '🍔'
  },
  model: {
    classes: 'from-gray-50 to-gray-100 border-gray-200 text-gray-900 group-hover:border-gray-300 dark:from-gray-800/40 dark:to-gray-700/40 dark:border-gray-600/50 dark:text-gray-200',
    icon: '🤖'
  },
  reading: {
    classes: 'from-indigo-50 to-blue-50 border-indigo-200 text-indigo-900 group-hover:border-indigo-300 dark:from-indigo-900/20 dark:to-blue-900/20 dark:border-indigo-700/50 dark:text-indigo-100',
    icon: '📚'
  },
  default: {
    classes: 'from-slate-50 to-slate-100 border-slate-200 text-slate-900 group-hover:border-slate-300 dark:from-slate-800/40 dark:to-slate-700/40 dark:border-slate-600/50 dark:text-slate-200',
    icon: '📂'
  }
}

function getSectionTheme(slug) {
  return themeMap[slug] || themeMap.default
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listSections({})
    const items = Array.isArray(data) ? data : (data.items || [])
    sections.value = items
  } catch (e) {
    error.value = '加载分区失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>

    <div v-if="loading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
    <div v-else>
      <div v-if="error" class="text-red-600 mb-3">{{ error }}</div>
      <div v-if="sections.length === 0" class="text-gray-600 dark:text-gray-300">暂无分区</div>
      <template v-else>
        <ul class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <li v-for="s in sections" :key="s.id" 
              class="section-card group relative overflow-hidden rounded-xl border transition-all duration-300 hover:-translate-y-1 hover:shadow-lg bg-gradient-to-br"
              :class="getSectionTheme(s.slug).classes">
            <router-link :to="{ name: 'discover', query: { sectionId: s.id } }" class="block p-5 h-full relative z-10">
              <div class="flex items-center justify-between mb-3">
                <div class="flex items-center gap-2">
                  <span class="text-2xl filter drop-shadow-sm">{{ getSectionTheme(s.slug).icon }}</span>
                  <h2 class="text-xl font-bold tracking-tight">{{ s.name || s.title }}</h2>
                </div>
                <span class="text-xs font-mono opacity-60 uppercase tracking-wider border border-current px-1.5 py-0.5 rounded-md">{{ s.slug }}</span>
              </div>
              <p class="text-sm opacity-80 leading-relaxed font-medium line-clamp-3">{{ s.description }}</p>
            </router-link>
            <!-- 装饰性背景纹理 -->
            <div class="absolute inset-0 opacity-[0.03] dark:opacity-[0.05] pointer-events-none bg-grid-pattern"></div>
          </li>
        </ul>
      </template>
    </div>
  </div>
</template>

<style scoped>
.bg-grid-pattern {
  background-image: radial-gradient(currentColor 1px, transparent 1px);
  background-size: 20px 20px;
}
.section-card {
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
}
.section-card:hover {
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
}
/* 增加微妙的内发光效果，提升质感 */
.section-card::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.5), transparent);
  opacity: 0.6;
}
.dark .section-card::after {
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.1), transparent);
}
</style>