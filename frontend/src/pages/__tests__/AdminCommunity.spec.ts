import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminCommunity from '../AdminCommunity.vue'

const mocks = vi.hoisted(() => ({
  getGovernanceMe: vi.fn(), listGovernanceUsers: vi.fn(), listGovernanceSections: vi.fn(),
  listGovernanceAudit: vi.fn(), updateGovernanceUserAdmin: vi.fn(),
  updateGovernanceModeratorSections: vi.fn(), updateGovernancePostingRestriction: vi.fn(),
  createGovernanceSection: vi.fn(), updateGovernanceSection: vi.fn(),
}))
vi.mock('@/api/governance', async (original) => ({
  ...await original<typeof import('@/api/governance')>(), ...mocks,
}))

const access = { admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null }
const member = { id: 7, username: 'member', ...access, admin: false }
const section = { id: 3, name: '交流', slug: 'chat', description: '', rules: '', visible: true, archived: false }
const empty = { items: [], page: 1, size: 20, total: 0 }
type UserPage = Omit<typeof empty, 'items'> & { items: Array<typeof member> }
let wrapper: VueWrapper | undefined

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

async function createWrapper() {
  wrapper = mount(AdminCommunity)
  await flushPromises()
  return wrapper
}

describe('AdminCommunity', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.setItem('accessToken', 'admin-session-a')
    mocks.getGovernanceMe.mockResolvedValue(access)
    mocks.listGovernanceUsers.mockResolvedValue({ ...empty, items: [member], total: 1 })
    mocks.listGovernanceSections.mockResolvedValue([section])
    mocks.listGovernanceAudit.mockResolvedValue(empty)
  })
  afterEach(() => { wrapper?.unmount(); localStorage.clear() })

  it('denies moderators without requesting admin datasets or clearing login', async () => {
    mocks.getGovernanceMe.mockResolvedValue({ ...access, admin: false, moderatedSectionIds: [3] })
    const view = await createWrapper()
    expect(view.text()).toContain('无权访问社区管理')
    expect(mocks.listGovernanceUsers).not.toHaveBeenCalled()
    expect(localStorage.getItem('accessToken')).toBe('admin-session-a')
  })

  it('starts member controls collapsed behind a native keyboard-accessible summary', async () => {
    const view = await createWrapper()
    const details = view.get('details.governance-member')
    expect(details.attributes('open')).toBeUndefined()
    expect(details.get('summary').text()).toContain('管理权限')
    expect(details.get('summary').text()).toContain('member')
  })

  it('rejects blank section reasons and keeps a failed submission editable', async () => {
    const view = await createWrapper()
    await view.get('#section-name').setValue('新分区')
    await view.get('#section-slug').setValue('new-section')
    await view.get('form.governance-section-form').trigger('submit')
    expect(mocks.createGovernanceSection).not.toHaveBeenCalled()
    await view.get('#section-reason').setValue('补充交流空间')
    mocks.createGovernanceSection.mockRejectedValue(new Error('offline'))
    await view.get('form.governance-section-form').trigger('submit')
    await flushPromises()
    expect(view.text()).toContain('操作失败')
    expect((view.get('#section-name').element as HTMLInputElement).value).toBe('新分区')
    expect((view.get('#section-reason').element as HTMLTextAreaElement).value).toBe('补充交流空间')
  })

  it('sends permanent posting restriction with null until and refreshes audit', async () => {
    const view = await createWrapper()
    const control = view.findAll('.governance-control')[2]!
    await control.get('input[type=checkbox]').setValue(true)
    await control.get('textarea').setValue('持续刷屏，待申诉复核')
    mocks.updateGovernancePostingRestriction.mockResolvedValue({ ...member, postingRestricted: true })
    await control.get('button').trigger('click')
    await flushPromises()
    expect(mocks.updateGovernancePostingRestriction).toHaveBeenCalledWith(7, {
      restricted: true, until: null, reason: '持续刷屏，待申诉复核',
    })
    expect(mocks.listGovernanceAudit).toHaveBeenCalledTimes(2)
    expect(view.text()).toContain('发帖限制已保存')
  })

  it('ignores a completed write after the account changes and clears old drafts', async () => {
    let resolveWrite!: (value: typeof member) => void
    mocks.updateGovernanceUserAdmin.mockImplementation(() => new Promise((resolve) => { resolveWrite = resolve }))
    const view = await createWrapper()
    await view.get('#admin-reason-7').setValue('交接管理员')
    await view.findAll('.governance-control')[0]!.get('button').trigger('click')
    localStorage.setItem('accessToken', 'ordinary-session-b')
    mocks.getGovernanceMe.mockResolvedValue({ ...access, admin: false })
    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await flushPromises()
    resolveWrite({ ...member, admin: true })
    await flushPromises()
    expect(view.text()).toContain('无权访问社区管理')
    expect(view.text()).not.toContain('管理员成功')
    expect(mocks.listGovernanceAudit).toHaveBeenCalledTimes(1)
  })

  it('ignores old user search results arriving after a newer search', async () => {
    let resolveOld!: (value: typeof empty) => void
    const view = await createWrapper()
    mocks.listGovernanceUsers.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
      .mockResolvedValueOnce({ ...empty, items: [{ ...member, username: 'latest-member' }], total: 1 })
    await view.get('#governance-user-search').setValue('old')
    await view.findAll('form')[0]!.trigger('submit')
    await view.get('#governance-user-search').setValue('latest')
    await view.findAll('form')[0]!.trigger('submit')
    await flushPromises()
    resolveOld(empty)
    await flushPromises()
    expect(view.text()).toContain('latest-member')
  })

  it('keeps aggregate refresh disabled while any initial resource is pending', async () => {
    const firstUsers = deferred<UserPage>()
    const firstSections = deferred<typeof section[]>()
    const firstAudit = deferred<typeof empty>()
    mocks.listGovernanceUsers.mockImplementationOnce(() => firstUsers.promise)
    mocks.listGovernanceSections.mockImplementationOnce(() => firstSections.promise)
    mocks.listGovernanceAudit.mockImplementationOnce(() => firstAudit.promise)

    const view = await createWrapper()
    expect(mocks.listGovernanceUsers).toHaveBeenCalledTimes(1)
    expect(mocks.listGovernanceSections).toHaveBeenCalledTimes(1)
    expect(mocks.listGovernanceAudit).toHaveBeenCalledTimes(1)

    firstUsers.resolve({ ...empty, items: [member], total: 1 })
    await flushPromises()
    const refresh = view.get('header button')
    expect(refresh.attributes('disabled')).toBeDefined()
    await refresh.trigger('click')
    expect(mocks.listGovernanceUsers).toHaveBeenCalledTimes(1)
    expect(mocks.listGovernanceSections).toHaveBeenCalledTimes(1)
    expect(mocks.listGovernanceAudit).toHaveBeenCalledTimes(1)

    firstSections.resolve([section])
    firstAudit.resolve(empty)
    await flushPromises()
  })

  it('coalesces repeated aggregate refresh attempts before resource requests settle', async () => {
    const firstUsers = deferred<UserPage>()
    const firstSections = deferred<typeof section[]>()
    const firstAudit = deferred<typeof empty>()
    mocks.listGovernanceUsers.mockImplementationOnce(() => firstUsers.promise)
    mocks.listGovernanceSections.mockImplementationOnce(() => firstSections.promise)
    mocks.listGovernanceAudit.mockImplementationOnce(() => firstAudit.promise)

    const view = await createWrapper()
    const refresh = view.get('header button')
    await refresh.trigger('click')
    await refresh.trigger('click')
    expect(mocks.listGovernanceUsers).toHaveBeenCalledTimes(1)
    expect(mocks.listGovernanceSections).toHaveBeenCalledTimes(1)
    expect(mocks.listGovernanceAudit).toHaveBeenCalledTimes(1)

    firstUsers.resolve({ ...empty, items: [member], total: 1 })
    firstSections.resolve([section])
    firstAudit.resolve(empty)
    await flushPromises()
  })

  it('guards user and audit pagination while that resource is loading', async () => {
    const auditEntry = { id: 9, action: 'ADMIN_ROLE_CHANGED', targetType: 'USER', targetId: 7, reason: 'r', actorId: 1, occurredAt: '2026-01-01T00:00:00Z' }
    mocks.listGovernanceUsers.mockResolvedValueOnce({ ...empty, items: [member], total: 40 })
    mocks.listGovernanceAudit.mockResolvedValueOnce({ ...empty, items: [auditEntry], total: 40 })
    const view = await createWrapper()

    const secondUsers = deferred<UserPage>()
    mocks.listGovernanceUsers.mockImplementationOnce(() => secondUsers.promise)
    const userNext = view.get('nav[aria-label="成员列表分页"] button:last-of-type')
    await userNext.trigger('click')
    await flushPromises()
    await userNext.trigger('click')
    expect(mocks.listGovernanceUsers).toHaveBeenCalledTimes(2)
    secondUsers.resolve({ ...empty, page: 2, items: [{ ...member, username: 'page-two-member' }], total: 40 })
    await flushPromises()
    expect(view.get('nav[aria-label="成员列表分页"]').text()).toContain('第 2 / 2 页')

    const secondAudit = deferred<Omit<typeof empty, 'items'> & { items: Array<typeof auditEntry> }>()
    mocks.listGovernanceAudit.mockImplementationOnce(() => secondAudit.promise)
    const auditNext = view.get('nav[aria-label="变更记录分页"] button:last-of-type')
    await auditNext.trigger('click')
    await flushPromises()
    await auditNext.trigger('click')
    expect(mocks.listGovernanceAudit).toHaveBeenCalledTimes(2)
    secondAudit.resolve({ ...empty, page: 2, items: [auditEntry], total: 40 })
    await flushPromises()
    expect(view.get('nav[aria-label="变更记录分页"]').text()).toContain('第 2 / 2 页')
  })

  it('allows one aggregate retry after a settled refresh failure', async () => {
    const view = await createWrapper()
    mocks.listGovernanceSections.mockRejectedValueOnce(new Error('offline'))

    await view.get('header button').trigger('click')
    await flushPromises()
    expect(mocks.listGovernanceUsers).toHaveBeenCalledTimes(2)
    expect(mocks.listGovernanceSections).toHaveBeenCalledTimes(2)
    expect(mocks.listGovernanceAudit).toHaveBeenCalledTimes(2)
    expect(view.text()).toContain('分区加载失败')

    await view.get('header button').trigger('click')
    await flushPromises()
    expect(mocks.listGovernanceUsers).toHaveBeenCalledTimes(3)
    expect(mocks.listGovernanceSections).toHaveBeenCalledTimes(3)
    expect(mocks.listGovernanceAudit).toHaveBeenCalledTimes(3)
  })
})
