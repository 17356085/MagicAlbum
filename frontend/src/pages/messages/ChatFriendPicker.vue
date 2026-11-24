<script setup lang="ts">
import { ref, watch } from 'vue'
import { friends } from '@/api/chat'
import type { User } from '@/types'

const props = defineProps<{ exclude?: number[]; limit: number }>()
const selected = defineModel<number[]>({ required: true })
const page = ref(1)
const total = ref(0)
const users = ref<User[]>([])
const loading = ref(false)
const failed = ref(false)

watch(page, async (value, _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  loading.value = true
  failed.value = false
  try {
    const result = await friends(value, 20)
    if (current) {
      users.value = result.items
      total.value = result.total
    }
  } catch {
    if (current) failed.value = true
  } finally {
    if (current) loading.value = false
  }
}, { immediate: true })

function toggle(id: number) {
  if (selected.value.includes(id)) selected.value = selected.value.filter(value => value !== id)
  else if (props.limit === 1) selected.value = [id]
  else if (selected.value.length < props.limit) selected.value = [...selected.value, id]
}
</script>

<template>
  <fieldset class="space-y-2">
    <legend class="text-sm font-medium">选择互关好友</legend>
    <p v-if="loading" role="status" class="ui-muted text-sm">正在加载好友…</p>
    <p v-else-if="failed" role="alert" class="ui-error">好友加载失败，请重新打开选择器。</p>
    <p v-else-if="!total" class="ui-muted text-sm">互相关注后，对方会出现在这里。</p>
    <div v-else class="flex flex-wrap gap-2">
      <template v-for="user in users" :key="user.id">
        <button
          v-if="!exclude?.includes(Number(user.id))"
          type="button" class="ui-button" :aria-pressed="selected.includes(Number(user.id))"
          :class="{ 'text-[var(--ui-accent)]': selected.includes(Number(user.id)) }"
          @click="toggle(Number(user.id))"
        >{{ selected.includes(Number(user.id)) ? '✓ ' : '' }}{{ user.nickname || user.username }}</button>
      </template>
    </div>
    <div v-if="total > 20" class="flex flex-wrap items-center gap-2 text-sm">
      <button type="button" class="ui-button" :disabled="page === 1 || loading" @click="page--">上一页</button>
      <span>{{ page }} / {{ Math.ceil(total / 20) }}</span>
      <button type="button" class="ui-button" :disabled="page * 20 >= total || loading" @click="page++">下一页</button>
    </div>
    <p v-if="selected.length" class="ui-muted text-sm">已选择 {{ selected.length }} 人</p>
  </fieldset>
</template>
