import { nextTick, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance, type ComputedRef, type Ref } from 'vue'
import type { Id } from '@/types'
import type { CommentGroup } from './types'

interface CommentUiStateOptions {
  autoCollapseCountThreshold: number
  autoCollapseHeightThreshold: number
  autoCollapseWidthThreshold: number
  childPageSize: number
  groups: ComputedRef<CommentGroup[]>
  scrollToPostId: Ref<number | null | undefined>
  threadId: Ref<number>
}

export function useCommentUiState(options: CommentUiStateOptions) {
  const collapsedMap = ref<Record<string, boolean>>({})
  const groupPageMap = ref<Record<string, number>>({})
  const groupEls = ref<Record<string, HTMLElement>>({})
  const highlightedPostId = ref<Id | null>(null)
  let resizeObserver: ResizeObserver | null = null
  let highlightTimer: ReturnType<typeof setTimeout> | null = null

  function storageKeyPages() {
    return `comments_group_pages_${String(options.threadId.value || '')}`
  }

  function storageKeyCollapsed() {
    return `comments_collapsed_map_${String(options.threadId.value || '')}`
  }

  function restoreStateFromStorage(): void {
    try {
      const rawPages = localStorage.getItem(storageKeyPages())
      if (rawPages) {
        const obj = JSON.parse(rawPages)
        if (obj && typeof obj === 'object') groupPageMap.value = obj
      }
    } catch (_) {}
    try {
      const rawCollapsed = localStorage.getItem(storageKeyCollapsed())
      if (rawCollapsed) {
        const obj = JSON.parse(rawCollapsed)
        if (obj && typeof obj === 'object') collapsedMap.value = obj
      }
    } catch (_) {}
  }

  function persistStateToStorage(): void {
    try { localStorage.setItem(storageKeyPages(), JSON.stringify(groupPageMap.value || {})) } catch (_) {}
    try { localStorage.setItem(storageKeyCollapsed(), JSON.stringify(collapsedMap.value || {})) } catch (_) {}
  }

  function toggleCollapse(rootId: Id): void {
    const key = String(rootId)
    collapsedMap.value[key] = !collapsedMap.value[key]
  }

  function setGroupEl(rootId: Id, el: Element | null): void {
    if (el instanceof HTMLElement) {
      groupEls.value[String(rootId)] = el
    }
  }

  function setGroupRef(rootId: Id, el: Element | ComponentPublicInstance | null): void {
    setGroupEl(rootId, el instanceof Element ? el : null)
  }

  function updateAutoCollapse(): void {
    options.groups.value.forEach((group) => {
      const key = String(group.root.id)
      const el = groupEls.value[key]
      const width = el?.clientWidth || 0
      const height = el?.scrollHeight || el?.clientHeight || 0
      const shouldCollapse =
        group.items.length >= options.autoCollapseCountThreshold ||
        width > options.autoCollapseWidthThreshold ||
        height > options.autoCollapseHeightThreshold

      if (typeof collapsedMap.value[key] === 'undefined') {
        collapsedMap.value[key] = shouldCollapse
      }
      if (typeof groupPageMap.value[key] === 'undefined') {
        groupPageMap.value[key] = 1
      }
    })
  }

  function getInputNumber(event: Event): number | null {
    const target = event.target as HTMLInputElement | null
    const value = Number(String(target?.value || '').replace(/[^0-9]/g, ''))
    return Number.isNaN(value) ? null : value
  }

  function updateGroupPageInput(rootId: Id, event: Event): void {
    const value = getInputNumber(event)
    if (value != null) {
      groupPageMap.value[String(rootId)] = value
    }
  }

  function clampGroupPage(rootId: Id, itemCount: number): void {
    const max = Math.max(1, Math.ceil(itemCount / options.childPageSize))
    const current = Number(groupPageMap.value[String(rootId)] || 1)
    groupPageMap.value[String(rootId)] = Math.min(Math.max(1, current || 1), max)
  }

  function getChildrenPage(group: CommentGroup) {
    const page = Number(groupPageMap.value[String(group.root.id)] || 1)
    const max = Math.max(1, Math.ceil((group.items?.length || 0) / options.childPageSize))
    const current = Math.min(Math.max(1, page), max)
    const start = (current - 1) * options.childPageSize
    return (group.items || []).slice(start, start + options.childPageSize)
  }

  function tryScrollToId(id: Id | null | undefined): void {
    const targetId = Number(id || 0)
    if (!targetId) return

    let targetGroup: CommentGroup | null = null
    let isRoot = false

    for (const group of options.groups.value) {
      if (Number(group.root?.id || 0) === targetId) {
        targetGroup = group
        isRoot = true
        break
      }
      const idx = (group.items || []).findIndex((item) => Number(item?.id || 0) === targetId)
      if (idx >= 0) {
        targetGroup = group
        break
      }
    }

    if (!targetGroup) return
    const rootId = Number(targetGroup.root?.id || 0)
    if (!rootId) return

    collapsedMap.value[String(rootId)] = false
    if (!isRoot) {
      const idx = (targetGroup.items || []).findIndex((item) => Number(item?.id || 0) === targetId)
      if (idx >= 0) {
        groupPageMap.value[String(rootId)] = Math.floor(idx / options.childPageSize) + 1
      }
    }

    nextTick(() => {
      try {
        const el = document.getElementById(`post-${targetId}`)
        if (el) {
          highlightedPostId.value = targetId
          el.scrollIntoView({ behavior: 'smooth', block: 'center' })
          if (highlightTimer) clearTimeout(highlightTimer)
          highlightTimer = setTimeout(() => {
            highlightedPostId.value = null
            highlightTimer = null
          }, 3200)
        }
      } catch (_) {}
    })
  }

  onMounted(() => {
    restoreStateFromStorage()
    updateAutoCollapse()
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => updateAutoCollapse())
    }
    nextTick(() => {
      if (resizeObserver) {
        Object.values(groupEls.value).forEach((el) => {
          if (el) resizeObserver?.observe(el)
        })
      }
    })
  })

  watch(groupPageMap, () => persistStateToStorage(), { deep: true })
  watch(collapsedMap, () => persistStateToStorage(), { deep: true })

  watch(options.groups, () => {
    nextTick(() => {
      updateAutoCollapse()
      if (resizeObserver) {
        resizeObserver.disconnect()
        Object.values(groupEls.value).forEach((el) => {
          if (el) resizeObserver?.observe(el)
        })
      }
    })
  })

  watch(options.scrollToPostId, (nextId, oldId) => {
    if (nextId && nextId !== oldId) {
      nextTick(() => tryScrollToId(nextId))
    }
  })

  watch(options.threadId, () => {
    collapsedMap.value = {}
    groupPageMap.value = {}
    restoreStateFromStorage()
    nextTick(() => updateAutoCollapse())
  })

  onBeforeUnmount(() => {
    if (resizeObserver) resizeObserver.disconnect()
    if (highlightTimer) clearTimeout(highlightTimer)
  })

  return {
    clampGroupPage,
    collapsedMap,
    getChildrenPage,
    groupPageMap,
    highlightedPostId,
    setGroupRef,
    toggleCollapse,
    tryScrollToId,
    updateGroupPageInput,
  }
}
