import { ref, watch } from 'vue'

// 轻量前端设置：仅本地持久化，不依赖后端
const storageKey = 'ui.dynamicBackgroundEnabled'
const dynamicBackgroundEnabled = ref(false)

// 初始化
try {
  const raw = localStorage.getItem(storageKey)
  dynamicBackgroundEnabled.value = raw === '1' || raw === 'true'
} catch (_) {}

// 持久化
watch(dynamicBackgroundEnabled, (val) => {
  try { localStorage.setItem(storageKey, val ? '1' : '0') } catch (_) {}
})

export function useUISettings() {
  const setDynamicBackgroundEnabled = (val) => { dynamicBackgroundEnabled.value = !!val }
  return { dynamicBackgroundEnabled, setDynamicBackgroundEnabled }
}