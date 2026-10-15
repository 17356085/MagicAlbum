<template>
  <div class="flex flex-wrap items-center justify-between gap-4">
    <div class="text-xs text-gray-500 dark:text-gray-400">
      <span v-if="total != null">共 {{ total }} 条 · 每页 {{ size }} 条</span>
    </div>
    <div class="flex max-w-full flex-wrap items-center gap-2">
      <button
        class="ui-button disabled:opacity-50"
        :disabled="page <= 1"
        @click="$emit('update:page', page - 1)"
      >上一页</button>
      <span class="text-sm text-gray-600 dark:text-gray-300">第</span>
      <input
        v-model="inputPage"
        class="ui-field !w-14 text-center text-sm"
        aria-label="页码"
        inputmode="numeric"
        @keyup.enter="goToInputPage"
        @blur="goToInputPage"
      />
      <span class="text-sm text-gray-600 dark:text-gray-300">/ {{ totalPages }} 页</span>
      <button
        class="ui-button disabled:opacity-50"
        :disabled="page >= totalPages"
        @click="$emit('update:page', page + 1)"
      >下一页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

interface PaginationProps {
  page?: number
  size?: number
  total?: number | null
}

const props = withDefaults(defineProps<PaginationProps>(), {
  page: 1,
  size: 20,
  total: null,
})

const emit = defineEmits<{
  'update:page': [value: number]
  'update:size': [value: number]
}>()

const totalPages = computed<number>(() => {
  if (!props.total || props.size <= 0) return 1
  return Math.max(1, Math.ceil(props.total / props.size))
})

const inputPage = ref(String(props.page || 1))

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(props.page || 1)
    return
  }
  emit('update:page', Math.min(Math.max(1, Number(raw)), totalPages.value))
}

watch(() => props.page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })
</script>
