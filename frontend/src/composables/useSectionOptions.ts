import { onUnmounted, ref } from 'vue'
import { listSections } from '@/api/sections'
import type { Section } from '@/types'

const SECTION_PAGE_SIZE = 100

export function useSectionOptions() {
  const sections = ref<Section[]>([])
  const sectionsLoading = ref(false)
  const sectionsError = ref('')

  let disposed = false

  async function loadSections(): Promise<void> {
    if (disposed || sectionsLoading.value) return

    const collected = new Map<string, Section>()
    let lastPage = Infinity
    sectionsLoading.value = true
    sectionsError.value = ''

    try {
      for (let page = 1; page <= lastPage; page += 1) {
        const response = await listSections({ page, size: SECTION_PAGE_SIZE })
        if (disposed) return
        if (response.page !== page) {
          throw new Error(`Unexpected sections page ${response.page}; expected ${page}`)
        }

        lastPage = Math.min(lastPage, Math.ceil(response.total / response.size))
        if (response.items.length === 0 && page <= lastPage) {
          throw new Error(`Unexpected empty sections page ${page}`)
        }

        for (const section of response.items) {
          const id = String(section.id)
          if (!collected.has(id)) collected.set(id, section)
        }
      }

      sections.value = [...collected.values()]
    } catch (_) {
      if (!disposed) sectionsError.value = '分区加载失败，请重试'
    } finally {
      if (!disposed) sectionsLoading.value = false
    }
  }

  onUnmounted(() => {
    disposed = true
  })

  return {
    sections,
    sectionsLoading,
    sectionsError,
    loadSections,
  }
}
