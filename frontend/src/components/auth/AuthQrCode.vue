<template>
  <div class="relative">
    <div v-if="dataUrl" class="relative mx-auto w-[152px] overflow-hidden rounded-lg border border-gray-200 bg-white p-2 dark:border-gray-700 dark:bg-gray-950">
      <div class="relative aspect-square w-full rounded border border-gray-200 bg-white p-2 dark:border-gray-300 dark:bg-white">
        <img :src="dataUrl" alt="二维码" class="block h-full w-full rounded bg-white object-contain [image-rendering:pixelated]" />

        <div class="pointer-events-none absolute left-2.5 top-2.5 h-5 w-5 rounded-tl-[7px] border-l-2 border-t-2 border-sky-400/90"></div>
        <div class="pointer-events-none absolute right-2.5 top-2.5 h-5 w-5 rounded-tr-[7px] border-r-2 border-t-2 border-sky-400/90"></div>
        <div class="pointer-events-none absolute bottom-2.5 left-2.5 h-5 w-5 rounded-bl-[7px] border-b-2 border-l-2 border-sky-400/90"></div>
        <div class="pointer-events-none absolute bottom-2.5 right-2.5 h-5 w-5 rounded-br-[7px] border-b-2 border-r-2 border-sky-400/90"></div>

        <div class="pointer-events-none absolute inset-0 flex items-center justify-center">
            <div class="flex h-9 w-9 items-center justify-center rounded border border-gray-200 bg-white dark:border-gray-200">
            <IconSite class="h-5 w-5" />
          </div>
        </div>
      </div>

      <div
        v-if="showOverlay"
        class="absolute inset-0 z-10 flex flex-col items-center justify-center gap-2 rounded-lg bg-white/95 dark:bg-gray-950/95"
      >
        <div class="ui-tag px-3 py-1 text-xs font-medium" :class="overlayBadgeClass">{{ overlayTitle }}</div>
        <p class="ui-muted px-6 text-center text-[11px] leading-4">{{ overlayText }}</p>
      </div>
    </div>
    <div
      v-else
      class="ui-muted mx-auto flex aspect-square w-[152px] items-center justify-center rounded-lg border border-gray-200 bg-[var(--ui-subtle)] p-4 text-center text-[11px] leading-4 dark:border-gray-700"
    >
      <span>{{ loading ? '二维码生成中…' : errorMessage || '二维码暂不可用' }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { computed } from 'vue'
import IconSite from '@/components/icons/IconSite.vue'
import type { QrLoginStatus } from '@/types'

const props = defineProps<{
  value: string
  status?: QrLoginStatus
}>()

const dataUrl = ref('')
const loading = ref(false)
const errorMessage = ref('')

const showOverlay = computed(() => props.status === 'EXPIRED' || props.status === 'CANCELLED')

const overlayTitle = computed(() => {
  if (props.status === 'EXPIRED') return '二维码已过期'
  if (props.status === 'CANCELLED') return '二维码已取消'
  return ''
})

const overlayText = computed(() => {
  if (props.status === 'EXPIRED') return '请刷新二维码后重新扫码'
  if (props.status === 'CANCELLED') return '当前会话已结束，请重新发起登录'
  return ''
})

const overlayBadgeClass = computed(() => {
  if (props.status === 'EXPIRED') return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
  if (props.status === 'CANCELLED') return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
  return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300'
})

async function renderQrCode(value: string): Promise<void> {
  const nextValue = String(value || '').trim()
  if (!nextValue) {
    dataUrl.value = ''
    errorMessage.value = ''
    return
  }

  loading.value = true
  errorMessage.value = ''
  try {
    const qr = await import('qrcode')
    dataUrl.value = await qr.toDataURL(nextValue, {
      margin: 2,
      width: 136,
      color: {
        dark: '#0f172a',
        light: '#ffffff',
      },
    })
  } catch (_) {
    dataUrl.value = ''
    errorMessage.value = '二维码生成失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.value, (value) => {
  void renderQrCode(value)
}, { immediate: true })

onMounted(() => {
  void renderQrCode(props.value)
})
</script>
