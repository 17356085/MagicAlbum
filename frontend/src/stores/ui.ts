import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useUIStore = defineStore('ui', () => {
  // State
  const dynamicBackgroundEnabled = ref<boolean>(false)
  const storageKey = 'ui.dynamicBackgroundEnabled'

  // Initialize
  try {
    const raw = localStorage.getItem(storageKey)
    dynamicBackgroundEnabled.value = raw === '1' || raw === 'true'
  } catch (_) {}

  // Persistence
  watch(dynamicBackgroundEnabled, (val: boolean) => {
    try { localStorage.setItem(storageKey, val ? '1' : '0') } catch (_) {}
  })

  // Actions
  function setDynamicBackgroundEnabled(val: boolean): void {
    dynamicBackgroundEnabled.value = !!val
  }

  return {
    dynamicBackgroundEnabled,
    setDynamicBackgroundEnabled,
  }
})
