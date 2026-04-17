<template>
  <AuthModal @close="emit('close')" @success="emit('success')" />
</template>

<script setup lang="ts">
import AuthModal from '@/components/auth/AuthModal.vue'

const emit = defineEmits<{
  close: []
  success: []
}>()
</script>
