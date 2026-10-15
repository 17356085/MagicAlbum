import { computed, onMounted, ref } from 'vue'
import { initializeTheme, persistTheme } from '@/utils/theme'

export function useHeaderTheme() {
  const isDark = ref(false)
  const themeLabel = computed(() => (isDark.value ? '切换为白天模式' : '切换为黑夜模式'))

  function toggleTheme(): void {
    isDark.value = !isDark.value
    persistTheme(isDark.value)
  }

  onMounted(() => {
    isDark.value = initializeTheme()
  })

  return { isDark, themeLabel, toggleTheme }
}
