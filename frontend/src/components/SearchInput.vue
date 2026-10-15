<template>
  <div class="flex min-w-0 items-center gap-2">
    <input
      v-model="localValue"
      type="text"
      aria-label="搜索"
      :placeholder="placeholder"
      class="ui-field min-w-0 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
      @keyup.enter="emitSearch"
    />
    <button
      type="button"
      class="ui-button-primary shrink-0 px-3 py-2 text-sm"
      @click="emitSearch"
    >
      搜索
    </button>
  </div>
  <div v-if="showReset && localValue" class="mt-2">
    <button type="button" class="ui-button px-2.5 py-1 text-xs" @click="reset">清除搜索</button>
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
