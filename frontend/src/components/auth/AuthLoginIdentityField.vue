<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue: string
  id: string
  errorId: string
  label: string
  type: 'tel' | 'email'
  placeholder: string
  error: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const model = computed({
  get: () => props.modelValue,
  set: (value: string) => emit('update:modelValue', value),
})
</script>

<template>
  <div>
    <label :for="id" class="mb-1 block text-sm font-medium">{{ label }}</label>
    <input
      v-model.trim="model"
      :id="id"
      :type="type"
      :placeholder="placeholder"
      :aria-invalid="Boolean(error)"
      :aria-describedby="error ? errorId : undefined"
      class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
    />
    <p v-if="error" :id="errorId" role="alert" class="mt-1 text-xs text-red-600">{{ error }}</p>
  </div>
</template>
