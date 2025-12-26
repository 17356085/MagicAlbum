<template>
  <div class="max-w-3xl mx-auto px-4 py-6">
    <div v-if="loading" class="text-center text-gray-500">加载中...</div>
    <div v-else-if="error" class="text-center text-red-500">{{ error }}</div>
    <div v-else class="space-y-6">
      <div class="flex items-center gap-4">
        <template v-if="profile.avatarUrl">
          <img :src="normalizeImageUrl(profile.avatarUrl)" alt="头像" class="w-20 h-20 rounded-full object-cover" />
        </template>
        <template v-else>
          <div class="w-20 h-20 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-2xl text-gray-600 dark:text-gray-300">
            {{ String(profile.nickname || profile.username || 'U').slice(0,1).toUpperCase() }}
          </div>
        </template>
        <div>
          <div class="text-xl font-semibold">{{ profile.nickname || profile.username || '未命名用户' }}</div>
          <div v-if="profile.homepageUrl" class="text-sm text-blue-600 dark:text-blue-400">
            <a :href="profile.homepageUrl" target="_blank" rel="noopener">{{ profile.homepageUrl }}</a>
          </div>
          <div v-if="profile.location" class="text-sm text-gray-600 dark:text-gray-300">{{ profile.location }}</div>
        </div>
      </div>

      <div>
        <div class="text-sm text-gray-500 mb-1">个人介绍</div>
        <div class="text-gray-800 dark:text-gray-200 whitespace-pre-wrap">{{ profile.bio || '这个人很神秘，什么都没有写。' }}</div>
      </div>

      <div v-if="profile.links && profile.links.length" class="space-y-2">
        <div class="text-sm text-gray-500">相关链接</div>
        <ul class="list-disc pl-6">
          <li v-for="(l, idx) in profile.links" :key="idx">
            <a :href="l.url || l" target="_blank" rel="noopener" class="text-blue-600 dark:text-blue-400">{{ l.title || l.url || l }}</a>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getUserProfile } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'

const route = useRoute()
const userId = route.params.id
const loading = ref(true)
const error = ref('')
const profile = ref({})

onMounted(async () => {
  try {
    const data = await getUserProfile(userId)
    profile.value = data || {}
  } catch (e) {
    error.value = (e && e.message) || '获取用户资料失败'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
</style>