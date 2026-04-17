<template>
  <div class="relative">
    <div v-if="dataUrl" class="relative mx-auto w-[152px] overflow-hidden rounded-[16px] border border-sky-100 bg-white p-2 shadow-[0_10px_24px_rgba(14,165,233,0.1)] dark:border-gray-700 dark:bg-gray-950">
      <div class="absolute inset-0 bg-[radial-gradient(circle_at_top,_rgba(224,242,254,0.55),_rgba(255,255,255,0)_48%)] dark:bg-[radial-gradient(circle_at_top,_rgba(8,145,178,0.16),_rgba(17,24,39,0)_48%)]"></div>
      <div class="relative aspect-square w-full rounded-[14px] border border-sky-100/80 bg-white p-2 dark:border-gray-700 dark:bg-white">
        <img :src="dataUrl" alt="二维码" class="block h-full w-full rounded-[10px] bg-white object-contain [image-rendering:pixelated]" />

        <div class="pointer-events-none absolute left-2.5 top-2.5 h-5 w-5 rounded-tl-[7px] border-l-2 border-t-2 border-sky-400/90"></div>
        <div class="pointer-events-none absolute right-2.5 top-2.5 h-5 w-5 rounded-tr-[7px] border-r-2 border-t-2 border-sky-400/90"></div>
        <div class="pointer-events-none absolute bottom-2.5 left-2.5 h-5 w-5 rounded-bl-[7px] border-b-2 border-l-2 border-sky-400/90"></div>
        <div class="pointer-events-none absolute bottom-2.5 right-2.5 h-5 w-5 rounded-br-[7px] border-b-2 border-r-2 border-sky-400/90"></div>

        <div class="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div class="flex h-9 w-9 items-center justify-center rounded-xl border border-sky-100 bg-white shadow-[0_8px_18px_rgba(15,23,42,0.16)] dark:border-gray-200">
            <IconMagicalbum class="h-5 w-5" />
          </div>
        </div>
      </div>

      <div
        v-if="showOverlay"
        class="absolute inset-0 z-10 flex flex-col items-center justify-center gap-2 rounded-[16px] bg-white/88 backdrop-blur-[2px] dark:bg-gray-950/88"
      >
        <div class="rounded-full px-3 py-1 text-xs font-medium" :class="overlayBadgeClass">{{ overlayTitle }}</div>
        <p class="px-6 text-center text-[11px] leading-4 text-gray-600 dark:text-gray-300">{{ overlayText }}</p>
      </div>
    </div>
    <div
      v-else
      class="mx-auto flex aspect-square w-[152px] items-center justify-center rounded-[16px] border border-sky-100 bg-[radial-gradient(circle_at_top,_rgba(224,242,254,0.9),_rgba(255,255,255,1)_58%)] p-4 text-center text-[11px] leading-4 text-gray-500 dark:border-gray-700 dark:bg-[radial-gradient(circle_at_top,_rgba(8,47,73,0.9),_rgba(17,24,39,1)_58%)] dark:text-gray-400"
    >
      <span>{{ loading ? '二维码生成中…' : errorMessage || '二维码暂不可用' }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { computed } from 'vue'
import IconMagicalbum from '@/components/icons/IconMagicalbum.vue'
import type { QrLoginStatus } from '@/types'

const props = defineProps<{
  value: string
  status?: QrLoginStatus
}>()

const dataUrl = ref('')
const loading = ref(false)
const errorMessage = ref('')

const showOverlay = computed(() => props.status === 'EXPIRED' || props.status === 'CANCELED')

const overlayTitle = computed(() => {
  if (props.status === 'EXPIRED') return '二维码已过期'
  if (props.status === 'CANCELED') return '二维码已取消'
  return ''
})

const overlayText = computed(() => {
  if (props.status === 'EXPIRED') return '请刷新二维码后重新扫码'
  if (props.status === 'CANCELED') return '当前会话已结束，请重新发起登录'
  return ''
})

const overlayBadgeClass = computed(() => {
  if (props.status === 'EXPIRED') return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
  if (props.status === 'CANCELED') return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
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
