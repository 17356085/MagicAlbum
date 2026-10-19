import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { initializeTheme, persistTheme } from '@/utils/theme'

export function useHeaderTheme() {
  const isDark = ref(false)
  const themeLabel = computed(() => (isDark.value ? '切换为白天模式' : '切换为黑夜模式'))
  let themeObserver: MutationObserver | null = null

  function syncThemeFromDocument(): void {
    isDark.value = document.documentElement.classList.contains('dark')
  }

  function toggleTheme(): void {
    isDark.value = !isDark.value
    persistTheme(isDark.value)
  }

  onMounted(() => {
    isDark.value = initializeTheme()
    if (typeof MutationObserver !== 'undefined') {
      themeObserver = new MutationObserver(syncThemeFromDocument)
      themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
    }
  })

  onBeforeUnmount(() => {
    themeObserver?.disconnect()
    themeObserver = null
  })

  return { isDark, themeLabel, toggleTheme }
}
