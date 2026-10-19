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

  it('flushes the synchronously captured snapshot to its original key', () => {
    const key = ref('thread-draft-v1:1')
    const source = ref<DraftValue>({ title: '' })
    const draft = useDraft(key, {
      sourceRef: source,
      autoSaveMs: 100,
      serialize: (value) => JSON.stringify(value),
    })

    draft.startAutoSave()
    source.value.title = '账号 A 的最后输入'
    key.value = 'thread-draft-v1:2'

    expect(draft.flushAutoSave()).toBe(true)
    expect(JSON.parse(localStorage.getItem('thread-draft-v1:1') || '{}')).toEqual({ title: '账号 A 的最后输入' })
    expect(localStorage.getItem('thread-draft-v1:2')).toBeNull()
    expect(draft.flushAutoSave()).toBe(false)
    draft.stopAutoSave()
  })

  it('does not write storage when there is no pending auto-save', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const source = ref<DraftValue>({ title: '' })
    const draft = useDraft('thread-draft-v1:guest', {
      sourceRef: source,
      autoSaveMs: 100,
      serialize: (value) => JSON.stringify(value),
    })

    draft.startAutoSave()
    expect(draft.flushAutoSave()).toBe(false)
    expect(setItem).not.toHaveBeenCalled()
    draft.stopAutoSave()
    setItem.mockRestore()
  })

  it('reports manual save failure when storage rejects the write', () => {
    const source = ref<DraftValue>({ title: '无法保存' })
    const draft = useDraft('thread-draft-v1:guest', {
      sourceRef: source,
      serialize: (value) => JSON.stringify(value),
    })
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })

    expect(draft.saveDraft()).toBe(false)
    setItem.mockRestore()
  })

  it('does not revive a cleared pending draft when leaving', () => {
    const source = ref<DraftValue>({ title: '' })
    const draft = useDraft('thread-draft-v1:guest', { sourceRef: source })
    draft.startAutoSave()
    source.value.title = '即刻清除'
    draft.clearDraft()
    draft.stopAutoSave({ flush: true })
    vi.runAllTimers()
    expect(localStorage.getItem('thread-draft-v1:guest')).toBeNull()
  })

  it('does not create a draft for an untouched form when stopped with flush', () => {
    const source = ref<DraftValue>({ title: '' })
    const draft = useDraft('thread-draft-v1:guest', { sourceRef: source })
    draft.startAutoSave()
    draft.stopAutoSave({ flush: true })
    expect(localStorage.getItem('thread-draft-v1:guest')).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
  })
})
