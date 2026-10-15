import { isRef, ref, watch } from 'vue'
import type { Ref, WatchStopHandle } from 'vue'

type DraftRestoreMode = 'fill-empty' | 'replace'

interface BeautifyMarkdownOptions {
  stringify?: {
    bullet?: '-' | '*' | '+'
    fences?: boolean
    listItemIndent?: 'one' | 'tab' | 'mixed'
    rule?: '-' | '_' | '*'
    tightLists?: boolean
  }
}

interface UseDraftOptions<T> {
  sourceRef?: Ref<T>
  autoSaveMs?: number
  serialize?: (val: T | undefined) => string
  deserialize?: (raw: string) => T
  restoreMode?: DraftRestoreMode
}

type DraftStorageKey = string | Ref<string> | (() => string)

// 标准化美化 Markdown：统一列表符号、启用围栏、紧凑列表等
export async function beautifyMarkdown(src: string | null | undefined, options: BeautifyMarkdownOptions = {}): Promise<string> {
  const {
    stringify = {
      bullet: '-',
      fences: true,
      listItemIndent: 'one',
      rule: '-',
      tightLists: true,
    },
  } = options
  try {
    const [{ unified }, { default: remarkParse }, { default: remarkGfm }, { default: remarkStringify }] = await Promise.all([
      import('unified'),
      import('remark-parse'),
      import('remark-gfm'),
      import('remark-stringify'),
    ])
    const file = await unified()
      .use(remarkParse)
      .use(remarkGfm)
      .use(remarkStringify, stringify)
      .process(String(src || ''))
    return String(file)
  } catch (_) {
    return String(src || '')
  }
}

// 通用草稿管理：本地存储 + 自动保存（防抖） + 恢复
export function useDraft<T>(key: DraftStorageKey, {
  sourceRef,
  autoSaveMs = 800,
  serialize = (val) => JSON.stringify(val ?? ''),
  deserialize = (raw) => JSON.parse(raw),
  restoreMode = 'fill-empty', // 'fill-empty' | 'replace'
}: UseDraftOptions<T> = {}) {
  const draftHasData = ref(false)
  let stopWatch: WatchStopHandle | null = null
  let timer: ReturnType<typeof setTimeout> | null = null

  const resolveKey = (): string => {
    try {
      const value = typeof key === 'function' ? key() : (isRef(key) ? key.value : key)
      return String(value || '').trim()
    } catch (_) {
      return ''
    }
  }

  const isEmpty = (v: unknown) => (
    v === null || v === undefined ||
    (typeof v === 'string' && v.trim() === '') ||
    (Array.isArray(v) && v.length === 0)
  )

  const refreshHasData = (): void => {
    const storageKey = resolveKey()
    if (!storageKey) {
      draftHasData.value = false
      return
    }
    try { draftHasData.value = !!localStorage.getItem(storageKey) } catch (_) { draftHasData.value = false }
  }

  const saveToKey = (storageKey: string, payload?: T): void => {
    if (!storageKey) return
    try {
      const val = payload !== undefined ? payload : (sourceRef ? sourceRef.value : undefined)
      const raw = serialize(val)
      localStorage.setItem(storageKey, raw)
      refreshHasData()
    } catch (_) {}
  }

  const saveDraft = (payload?: T): void => {
    saveToKey(resolveKey(), payload)
  }

  const loadDraft = (): T | null => {
    const storageKey = resolveKey()
    if (!storageKey) return null
    try {
      const raw = localStorage.getItem(storageKey)
      if (!raw) return null
      return deserialize(raw)
    } catch (_) {
      return null
    }
  }

  const restoreDraft = (): T | null => {
    const data = loadDraft()
    if (data == null) return null
    if (!sourceRef) return data
    if (restoreMode === 'replace') {
      sourceRef.value = data
    } else {
      const cur = sourceRef.value as T | Record<string, unknown>
      if (cur && typeof cur === 'object' && data && typeof data === 'object') {
        const curRecord = cur as Record<string, unknown>
        const dataRecord = data as Record<string, unknown>
        for (const k of Object.keys(dataRecord)) {
          if (isEmpty(curRecord[k])) curRecord[k] = dataRecord[k]
        }
      } else if (isEmpty(cur)) {
        sourceRef.value = data
      }
    }
    return data
  }

  const clearTimer = (): void => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  const clearDraft = (): void => {
    clearTimer()
    const storageKey = resolveKey()
    if (!storageKey) {
      draftHasData.value = false
      return
    }
    try {
      localStorage.removeItem(storageKey)
      refreshHasData()
    } catch (_) {}
  }

  const startAutoSave = (): void => {
    if (!sourceRef) return
    stopAutoSave()
    stopWatch = watch(sourceRef, (val) => {
      clearTimer()
      const scheduledKey = resolveKey()
      timer = setTimeout(() => {
        timer = null
        // A key can change while the debounce is waiting. Do not let an old
        // account's payload land in the new account's storage slot.
        if (resolveKey() !== scheduledKey) return
        saveToKey(scheduledKey, val)
      }, autoSaveMs)
    }, { deep: true })
  }

  const stopAutoSave = (): void => {
    if (stopWatch) { try { stopWatch() } catch (_) {} ; stopWatch = null }
    clearTimer()
  }

  // 初始化草稿存在状态
  refreshHasData()

  return {
    draftHasData,
    saveDraft,
    loadDraft,
    restoreDraft,
    clearDraft,
    refreshDraft: refreshHasData,
    startAutoSave,
    stopAutoSave,
  }
}
