import { ref, watch } from 'vue'
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
export function useDraft<T>(key: string, {
  sourceRef,
  autoSaveMs = 800,
  serialize = (val) => JSON.stringify(val ?? ''),
  deserialize = (raw) => JSON.parse(raw),
  restoreMode = 'fill-empty', // 'fill-empty' | 'replace'
}: UseDraftOptions<T> = {}) {
  const draftHasData = ref(false)
  let stopWatch: WatchStopHandle | null = null
  let timer: ReturnType<typeof setTimeout> | null = null

  const isEmpty = (v: unknown) => (
    v === null || v === undefined ||
    (typeof v === 'string' && v.trim() === '') ||
    (Array.isArray(v) && v.length === 0)
  )

  const refreshHasData = (): void => {
    try { draftHasData.value = !!localStorage.getItem(key) } catch (_) { draftHasData.value = false }
  }

  const saveDraft = (payload?: T): void => {
    try {
      const val = payload !== undefined ? payload : (sourceRef ? sourceRef.value : undefined)
      const raw = serialize(val)
      localStorage.setItem(key, raw)
      refreshHasData()
    } catch (_) {}
  }

  const loadDraft = (): T | null => {
    try {
      const raw = localStorage.getItem(key)
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

  const clearDraft = (): void => {
    try {
      localStorage.removeItem(key)
      refreshHasData()
    } catch (_) {}
  }

  const startAutoSave = (): void => {
    if (!sourceRef) return
    stopAutoSave()
    stopWatch = watch(sourceRef, (val) => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => saveDraft(val), autoSaveMs)
    }, { deep: true })
  }

  const stopAutoSave = (): void => {
    if (stopWatch) { try { stopWatch() } catch (_) {} ; stopWatch = null }
    if (timer) { clearTimeout(timer); timer = null }
  }

  // 初始化草稿存在状态
  refreshHasData()

  return {
    draftHasData,
    saveDraft,
    loadDraft,
    restoreDraft,
    clearDraft,
    startAutoSave,
    stopAutoSave,
  }
}
