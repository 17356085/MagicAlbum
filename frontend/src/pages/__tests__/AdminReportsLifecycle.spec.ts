import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminReports from '../AdminReports.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({ access: vi.fn(), list: vi.fn(), resolve: vi.fn() }))
vi.mock('@/api/governance', () => ({ getGovernanceMe: mocks.access }))
vi.mock('@/api/admin', () => ({
  listAdminReports: mocks.list,
  resolveAdminReport: mocks.resolve,
  validateAdminReportReason: (value: string) => value.trim(),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const report = {
  id: 17, targetType: 'THREAD', targetId: 100, reason: 'OTHER', detail: 'A report',
  status: 'PENDING', createdAt: '2026-10-24T00:00:00Z', reporterId: 9,
}
const page = (detail = 'A report') => ({ items: [{ ...report, detail }], page: 1, size: 20, total: 1 })
const wrappers: VueWrapper[] = []
async function openReview() {
  const wrapper = mount(AdminReports, { global: { stubs: { RouterLink: true } } })
  wrappers.push(wrapper)
  await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '驳回举报')!.trigger('click')
  await wrapper.get('textarea').setValue('valid reason')
  return wrapper
}
async function submit(wrapper: VueWrapper) {
  await wrapper.findAll('form').find(form => form.find('textarea').exists())!.trigger('submit')
}

describe('AdminReports follow-up ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'admin-a', user: { id: 1, username: 'A' } })
    mocks.access.mockReset().mockResolvedValue({ admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null })
    mocks.list.mockReset().mockResolvedValue(page())
    mocks.resolve.mockReset()
  })
  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    localStorage.clear()
  })

  it.each(['unmount', 'storage-drift'])('does not read a new list after resolution is superseded by %s', async mode => {
    const mutation = deferred<void>()
    mocks.resolve.mockReturnValueOnce(mutation.promise)
    const wrapper = await openReview()
    await submit(wrapper)
    if (mode === 'unmount') wrapper.unmount()
    else localStorage.setItem('accessToken', 'admin-b')
    mutation.resolve()
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(1)
    if (mode === 'storage-drift') {
      expect(wrapper.text()).not.toContain('A report')
      expect(wrapper.text()).toContain('账号已变化')
    }
  })

  it('does not invalidate the new account list when an old resolution finishes', async () => {
    const mutation = deferred<void>()
    const nextList = deferred<ReturnType<typeof page>>()
    mocks.resolve.mockReturnValueOnce(mutation.promise)
    mocks.list.mockResolvedValueOnce(page()).mockReturnValueOnce(nextList.promise)
    const wrapper = await openReview()
    await submit(wrapper)
    useAuthStore().applyAuthState({ accessToken: 'admin-b', user: { id: 2, username: 'B' } })
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    mutation.resolve()
    await flushPromises()
    nextList.resolve(page('B report'))
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('B report')
    expect(wrapper.text()).not.toContain('驳回举报成功')
    expect(wrapper.find('[role="status"]').text()).not.toContain('正在验证')
  })

  it('rejects a submission whose opened review belongs to a replaced storage session', async () => {
    const wrapper = await openReview()
    localStorage.setItem('accessToken', 'admin-b')
    await submit(wrapper)
    await flushPromises()
    expect(mocks.resolve).not.toHaveBeenCalled()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('A report')
    await wrapper.findAll('button').find(button => button.text() === '重新验证')!.trigger('click')
    await flushPromises()
    expect(mocks.access).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('账号已变化')
    expect(wrapper.text()).not.toContain('正在验证')
  })

  it('coalesces aggregate refreshes before the rendered disabled state updates', async () => {
    const wrapper = await openReview()
    const nextList = deferred<ReturnType<typeof page>>()
    mocks.list.mockReturnValue(nextList.promise)
    const button = wrapper.findAll('button').find(item => item.text() === '刷新列表')!
    const first = button.trigger('click')
    const second = button.trigger('click')
    await Promise.all([first, second])
    expect(mocks.list).toHaveBeenCalledTimes(2)
    nextList.resolve(page())
    await flushPromises()
    expect(button.attributes('disabled')).toBeUndefined()
  })
})
