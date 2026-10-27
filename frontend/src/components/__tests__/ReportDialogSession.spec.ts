import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReportDialog from '../ReportDialog.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({ createReport: vi.fn() }))
vi.mock('@/api/reports', () => mocks)
let wrapper: VueWrapper | undefined

function deferred() {
  let resolve!: (value: unknown) => void
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

function open() {
  wrapper = mount(ReportDialog, { props: { modelValue: true, targetType: 'THREAD', targetId: 7, isLoggedIn: true } })
  return wrapper
}

function submit() {
  const form = document.body.querySelector('form')
  if (!form) throw new Error('Missing report form')
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
  mocks.createReport.mockReset()
  mocks.createReport.mockResolvedValue({ id: 1 })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
})

describe('ReportDialog session and target ownership', () => {
  it('rejects a submission after storage changes before the auth bridge runs', async () => {
    open()
    await flushPromises()
    localStorage.setItem('accessToken', 'token-b')
    submit()
    await flushPromises()
    expect(mocks.createReport).not.toHaveBeenCalled()
    expect(document.body.querySelector('[role="alert"]')?.textContent).toContain('账号状态已变更')
  })

  it('does not apply an old success to a reopened dialog or clear its new busy state', async () => {
    const older = deferred()
    const newer = deferred()
    mocks.createReport.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const dialog = open()
    submit()
    await dialog.setProps({ targetId: 8 })
    expect(dialog.emitted('update:modelValue')).toEqual([[false]])
    await dialog.setProps({ modelValue: false })
    await dialog.setProps({ modelValue: true })
    submit()
    await flushPromises()
    expect(mocks.createReport).toHaveBeenNthCalledWith(2, { targetType: 'THREAD', targetId: 8, reason: 'SPAM' })
    older.resolve({ id: 7 })
    await flushPromises()
    expect(document.body.querySelector('[role="status"]')).toBeNull()
    expect((document.body.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(true)
    newer.resolve({ id: 8 })
    await flushPromises()
    expect(document.body.querySelector('[role="status"]')?.textContent).toContain('举报已提交')
  })

  it('closes and ignores a late response when another account takes over', async () => {
    const pending = deferred()
    mocks.createReport.mockReturnValueOnce(pending.promise)
    const dialog = open()
    submit()
    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    pending.resolve({ id: 7 })
    await flushPromises()
    expect(dialog.emitted('update:modelValue')).toEqual([[false]])
    expect((dialog.vm as unknown as { successMessage: string }).successMessage).toBe('')
  })

  it('preserves a pending report through same-token identity hydration', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: null })
    const pending = deferred()
    mocks.createReport.mockReturnValueOnce(pending.promise)
    const dialog = open()
    submit()
    expect(auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'token-a')).toBe(true)
    pending.resolve({ id: 7 })
    await flushPromises()
    expect(dialog.emitted('update:modelValue')).toBeUndefined()
    expect(document.body.querySelector('[role="status"]')?.textContent).toContain('举报已提交')
  })

  it('does not mutate an unmounted form after an issued request completes', async () => {
    const pending = deferred()
    mocks.createReport.mockReturnValueOnce(pending.promise)
    const dialog = open()
    submit()
    dialog.unmount()
    wrapper = undefined
    pending.resolve({ id: 7 })
    await flushPromises()
    expect((dialog.vm as unknown as { successMessage: string }).successMessage).toBe('')
  })

  it('closes instead of leaving a busy form when storage drifts during submission', async () => {
    const pending = deferred()
    mocks.createReport.mockReturnValueOnce(pending.promise)
    const dialog = open()
    submit()
    localStorage.setItem('accessToken', 'token-b')
    pending.resolve({ id: 7 })
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    expect(dialog.emitted('update:modelValue')).toEqual([[false]])
  })

  it('preserves the current report when a different login attempt only changes auth generation', async () => {
    const pending = deferred()
    mocks.createReport.mockReturnValueOnce(pending.promise)
    const dialog = open()
    submit()
    useAuthStore().invalidateAuthOperations()
    pending.resolve({ id: 7 })
    await flushPromises()
    expect(dialog.emitted('update:modelValue')).toBeUndefined()
    expect(document.body.querySelector('[role="status"]')?.textContent).toContain('举报已提交')
  })

  it('keeps keyboard focus inside the report while open and restores the launcher on close', async () => {
    const launcher = document.createElement('button')
    document.body.appendChild(launcher)
    launcher.focus()
    try {
      open()
      await flushPromises()
      const first = document.body.querySelector<HTMLButtonElement>('button[aria-label="关闭举报对话框"]')!
      const last = document.body.querySelector<HTMLButtonElement>('button[type="submit"]')!
      last.focus()
      const tab = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true, bubbles: true })
      last.dispatchEvent(tab)
      expect(tab.defaultPrevented).toBe(true)
      expect(document.activeElement).toBe(first)
      first.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, cancelable: true, bubbles: true }))
      expect(document.activeElement).toBe(last)
      first.click()
      await flushPromises()
      expect(document.activeElement).toBe(launcher)
    } finally { launcher.remove() }
  })
})
