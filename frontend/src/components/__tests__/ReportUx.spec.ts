import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ReportDialog from '../ReportDialog.vue'
import CommentsList from '../comments/CommentsList.vue'
import ThreadDetailActions from '../../pages/thread-detail/ThreadDetailActions.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({
  createReport: vi.fn(),
  getThreadBookmarkState: vi.fn(),
  getThreadLikeState: vi.fn(),
}))

vi.mock('@/api/reports', () => ({
  createReport: mocks.createReport,
}))

vi.mock('@/api/bookmarks', () => ({
  getThreadBookmarkState: mocks.getThreadBookmarkState,
  bookmarkThread: vi.fn(),
  unbookmarkThread: vi.fn(),
}))

vi.mock('@/api/threads', () => ({
  getThreadBookmarkState: vi.fn(),
  getThreadLikeState: mocks.getThreadLikeState,
  likeThread: vi.fn(),
  unlikeThread: vi.fn(),
}))

function closeOpenDialog(): void {
  document.body.querySelectorAll('[data-report-dialog-backdrop]').forEach((element) => element.remove())
}

function findReportDialog(): HTMLElement {
  const dialog = document.body.querySelector('[role="dialog"]')
  if (!(dialog instanceof HTMLElement)) throw new Error('report dialog not found')
  return dialog
}

function createCommentsListProps(isLoggedIn = true) {
  const root = {
    id: 21,
    threadId: 7,
    authorId: 1,
    authorUsername: 'root-user',
    authorNickname: 'root-user',
    content: 'root comment',
    createdAt: '2026-10-08T00:00:00Z',
  }
  const child = {
    id: 22,
    threadId: 7,
    authorId: 2,
    authorUsername: 'child-user',
    authorNickname: 'child-user',
    content: 'child comment',
    createdAt: '2026-10-08T00:01:00Z',
  }
  const optimistic = {
    id: 'temp-1',
    threadId: 7,
    authorId: 3,
    authorUsername: 'new-user',
    authorNickname: 'new-user',
    content: 'optimistic comment',
    createdAt: '2026-10-08T00:02:00Z',
    _optimistic: true,
  }
  const group = { root, items: [child, optimistic] }

  return {
    childPageSize: 10,
    collapsedMap: {},
    error: '',
    getChildrenPage: () => group.items,
    groupPageMap: {},
    hasPagination: false,
    highlightedPostId: null,
    isLoggedIn,
    loading: false,
    modelValue: '',
    page: 1,
    pageCount: 1,
    pagedGroups: [group],
    previewMode: false,
    replyToPostId: null,
    setGroupRef: vi.fn(),
    showRepliesMenu: false,
    showTimeMenu: false,
    sortKey: 'time' as const,
    sortOrder: 'desc' as const,
  }
}

describe('report UX', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    closeOpenDialog()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'real-token' })
    mocks.getThreadLikeState.mockResolvedValue({ liked: false, likeCount: 0 })
    mocks.getThreadBookmarkState.mockResolvedValue({ bookmarked: false })
    mocks.createReport.mockResolvedValue({ id: 1, status: 'PENDING' })
  })

  it('submits a thread payload once, disables duplicate submits, and shows success inline', async () => {
    let resolveReport!: (value: unknown) => void
    mocks.createReport.mockReturnValueOnce(new Promise((resolve) => { resolveReport = resolve }))

    const wrapper = mount(ThreadDetailActions, {
      props: { thread: { id: 7, title: '举报帖子', liked: false, likeCount: 0 } },
    })
    await flushPromises()
    const trigger = wrapper.get('button[aria-label="举报"]')
    await trigger.trigger('click')

    const dialog = findReportDialog()
    const select = dialog.querySelector('select') as HTMLSelectElement
    const detail = dialog.querySelector('textarea') as HTMLTextAreaElement
    const submit = dialog.querySelector('button[type="submit"]') as HTMLButtonElement
    select.value = 'HARASSMENT'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    detail.value = '具体说明'
    detail.dispatchEvent(new Event('input', { bubbles: true }))
    submit.click()
    submit.click()

    expect(mocks.createReport).toHaveBeenCalledTimes(1)
    expect(mocks.createReport).toHaveBeenCalledWith({
      targetType: 'THREAD',
      targetId: 7,
      reason: 'HARASSMENT',
      detail: '具体说明',
    })
    await nextTick()
    expect(submit.disabled).toBe(true)

    resolveReport({ id: 1, status: 'PENDING' })
    await flushPromises()
    expect(findReportDialog().textContent).toContain('举报已提交')
    wrapper.unmount()
  })

  it('keeps the dialog open for an error and allows a retry, including keyboard close', async () => {
    mocks.createReport
      .mockRejectedValueOnce({ response: { status: 503, data: { message: '服务暂不可用' } } })
      .mockResolvedValueOnce({ id: 2, status: 'PENDING' })
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    trigger.focus()
    const wrapper = mount(ReportDialog, {
      props: { modelValue: true, targetType: 'POST', targetId: 22, isLoggedIn: true },
    })
    await flushPromises()

    const dialog = findReportDialog()
    const submit = dialog.querySelector('button[type="submit"]') as HTMLButtonElement
    submit.click()
    await flushPromises()
    expect(findReportDialog().textContent).toContain('服务暂不可用')
    expect(submit.disabled).toBe(false)

    submit.click()
    await flushPromises()
    expect(mocks.createReport).toHaveBeenCalledTimes(2)
    expect(findReportDialog().textContent).toContain('举报已提交')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    await wrapper.setProps({ modelValue: false })
    expect(document.activeElement).toBe(trigger)
    trigger.remove()
    wrapper.unmount()
  })

  it('shows a permission message for a message-less 403 without logging out', async () => {
    mocks.createReport.mockRejectedValueOnce({ response: { status: 403, data: {} } })
    const auth = useAuthStore()
    const wrapper = mount(ReportDialog, {
      props: { modelValue: true, targetType: 'POST', targetId: 22, isLoggedIn: true },
    })
    await flushPromises()

    const submit = findReportDialog().querySelector('button[type="submit"]') as HTMLButtonElement
    submit.click()
    await flushPromises()

    expect(findReportDialog().textContent).toContain('举报权限')
    expect(submit.disabled).toBe(false)
    expect(auth.isLoggedIn).toBe(true)
    expect(auth.token).toBe('real-token')
    wrapper.unmount()
  })

  it('sends POST reports for real comments, hides optimistic controls, and cues anonymous users', async () => {
    const wrapper = mount(CommentsList, {
      props: createCommentsListProps(true),
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    const reportButtons = wrapper.findAll('button').filter((button) => button.text() === '举报')
    expect(reportButtons).toHaveLength(2)

    await reportButtons[1].trigger('click')
    const dialog = findReportDialog()
    ;(dialog.querySelector('button[type="submit"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(mocks.createReport).toHaveBeenCalledWith({ targetType: 'POST', targetId: 22, reason: 'SPAM' })
    wrapper.unmount()

    const anonymousWrapper = mount(CommentsList, {
      props: createCommentsListProps(false),
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    const anonymousReportButton = anonymousWrapper.findAll('button').find((button) => button.text() === '举报')
    expect(anonymousReportButton).toBeDefined()
    await anonymousReportButton!.trigger('click')
    expect(mocks.createReport).toHaveBeenCalledTimes(1)
    expect(anonymousWrapper.text()).toContain('请先登录后再举报')
    anonymousWrapper.unmount()
  })
})
