import { nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDraft } from '../useMarkdownTools'

interface DraftValue {
  title: string
}

describe('useDraft dynamic ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not let a pending debounce cross into a newly selected key', async () => {
    const key = ref('thread-draft-v1:1')
    const source = ref<DraftValue>({ title: '' })
    const draft = useDraft(key, {
      sourceRef: source,
      autoSaveMs: 100,
      serialize: (value) => JSON.stringify(value),
      deserialize: (raw) => JSON.parse(raw) as DraftValue,
    })

    draft.startAutoSave()
    source.value.title = '账号 A'
    await nextTick()
    key.value = 'thread-draft-v1:2'
    source.value.title = '账号 B'
    await nextTick()
    vi.advanceTimersByTime(100)

    expect(localStorage.getItem('thread-draft-v1:1')).toBeNull()
    expect(JSON.parse(localStorage.getItem('thread-draft-v1:2') || '{}')).toEqual({ title: '账号 B' })
    draft.stopAutoSave()
  })

  it('cancels the watcher and timer when stopped', async () => {
    const source = ref<DraftValue>({ title: '' })
    const draft = useDraft('thread-draft-v1:guest', {
      sourceRef: source,
      autoSaveMs: 100,
      serialize: (value) => JSON.stringify(value),
    })

    draft.startAutoSave()
    source.value.title = '未完成'
    await nextTick()
    draft.stopAutoSave()
    vi.advanceTimersByTime(100)

    expect(localStorage.getItem('thread-draft-v1:guest')).toBeNull()
    source.value.title = '停止后变更'
    await nextTick()
    vi.advanceTimersByTime(100)
    expect(localStorage.getItem('thread-draft-v1:guest')).toBeNull()
  })
})
