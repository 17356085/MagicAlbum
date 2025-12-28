import { ref, watch } from 'vue'
import { unified } from 'unified'
import remarkParse from 'remark-parse'
import remarkGfm from 'remark-gfm'
import remarkStringify from 'remark-stringify'

// 标准化美化 Markdown：统一列表符号、启用围栏、紧凑列表等
export async function beautifyMarkdown(src, options = {}) {
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
export function useDraft(key, {
  sourceRef,
  autoSaveMs = 800,
  serialize = (val) => JSON.stringify(val ?? ''),
  deserialize = (raw) => JSON.parse(raw),
  restoreMode = 'fill-empty', // 'fill-empty' | 'replace'
} = {}) {
  const draftHasData = ref(false)
  let stopWatch = null
  let timer = null

  const isEmpty = (v) => (
    v === null || v === undefined ||
    (typeof v === 'string' && v.trim() === '') ||
    (Array.isArray(v) && v.length === 0)
  )

  const refreshHasData = () => {
    try { draftHasData.value = !!localStorage.getItem(key) } catch (_) { draftHasData.value = false }
  }

  const saveDraft = (payload) => {
    try {
      const val = payload !== undefined ? payload : (sourceRef ? sourceRef.value : undefined)
      const raw = serialize(val)
      localStorage.setItem(key, raw)
      refreshHasData()
    } catch (_) {}
  }

  const loadDraft = () => {
    try {
      const raw = localStorage.getItem(key)
      if (!raw) return null
      return deserialize(raw)
    } catch (_) {
      return null
    }
  }

  const restoreDraft = () => {
    const data = loadDraft()
    if (data == null) return null
    if (!sourceRef) return data
    if (restoreMode === 'replace') {
      sourceRef.value = data
    } else {
      const cur = sourceRef.value
      if (cur && typeof cur === 'object' && data && typeof data === 'object') {
        for (const k of Object.keys(data)) {
          if (isEmpty(cur[k])) cur[k] = data[k]
        }
      } else if (isEmpty(cur)) {
        sourceRef.value = data
      }
    }
    return data
  }

  const clearDraft = () => {
    try {
      localStorage.removeItem(key)
      refreshHasData()
    } catch (_) {}
  }

  const startAutoSave = () => {
    if (!sourceRef) return
    stopAutoSave()
    stopWatch = watch(sourceRef, (val) => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => saveDraft(val), autoSaveMs)
    }, { deep: true })
  }

  const stopAutoSave = () => {
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