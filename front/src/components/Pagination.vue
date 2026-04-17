<template>
  <div class="flex items-center justify-between gap-2">
    <div class="text-sm text-gray-600">
      第 {{ page }} / {{ totalPages }} 页
      <span v-if="total != null">（共 {{ total }} 条）</span>
    </div>
    <div class="flex items-center gap-2">
      <button
        class="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:hover:bg-gray-700"
        :disabled="page <= 1"
        @click="$emit('update:page', page - 1)"
      >上一页</button>
      <select
        class="rounded-md border border-gray-300 bg-white px-2 py-2 text-sm text-gray-700 hover:bg-gray-50 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:hover:bg-gray-700"
        :value="size"
        @change="onSizeChange"
      >
        <option :value="10">10/页</option>
        <option :value="20">20/页</option>
        <option :value="50">50/页</option>
      </select>
      <button
        class="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:hover:bg-gray-700"
        :disabled="page >= totalPages"
        @click="$emit('update:page', page + 1)"
      >下一页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

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

function onSizeChange(event: Event): void {
  const target = event.target as HTMLSelectElement | null
  emit('update:size', Number(target?.value || props.size))
}
</script>
