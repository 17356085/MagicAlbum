<template>
  <div class="flex items-center gap-2">
    <input
      v-model="localValue"
      type="text"
      :placeholder="placeholder"
      class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:focus:border-accentCyan-500 dark:focus:ring-accentCyan-500"
      @keyup.enter="emitSearch"
    />
    <button
      class="inline-flex items-center rounded-md bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-sm font-medium text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-500 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md"
      @click="emitSearch"
    >
      搜索
    </button>
  </div>
  <div v-if="showReset && localValue" class="mt-1">
    <button class="text-xs text-gray-500 hover:text-gray-700" @click="reset">清除搜索</button>
  </div>
  </template>

<script setup lang="ts">
import { ref, watch } from 'vue'

interface SearchInputProps {
  modelValue?: string
  placeholder?: string
  showReset?: boolean
}

const props = withDefaults(defineProps<SearchInputProps>(), {
  modelValue: '',
  placeholder: '搜索分区名称或描述',
  showReset: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  search: [value: string]
}>()
const localValue = ref(props.modelValue)

watch(() => props.modelValue, (v) => { localValue.value = v })
watch(localValue, (v) => emit('update:modelValue', v))

function emitSearch(): void { emit('search', localValue.value) }
function reset(): void { localValue.value = ''; emitSearch() }
</script>
