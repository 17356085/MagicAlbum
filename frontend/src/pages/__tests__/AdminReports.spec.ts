import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminReports from '../AdminReports.vue'

const mocks = vi.hoisted(() => ({
  getAdminStatus: vi.fn(),
  listAdminReports: vi.fn(),
  resolveAdminReport: vi.fn(),
}))

vi.mock('@/api/admin', () => ({
  getAdminStatus: mocks.getAdminStatus,
  listAdminReports: mocks.listAdminReports,
  resolveAdminReport: mocks.resolveAdminReport,
}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

const emptyPage = { items: [], page: 1, size: 20, total: 0 }

function createWrapper() {
  return mount(AdminReports, {
    global: {
      stubs: { RouterLink: RouterLinkStub },
    },
  })
}

describe('AdminReports', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows access denied on a server 403 without logging out or loading reports', async () => {
    mocks.getAdminStatus.mockRejectedValue({ response: { status: 403 } })
    const wrapper = createWrapper()

    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('无权访问举报审核')
    expect(mocks.listAdminReports).not.toHaveBeenCalled()
  })

  it('renders Chinese enum labels and only links reports to known thread targets', async () => {
    mocks.getAdminStatus.mockResolvedValue(true)
    mocks.listAdminReports.mockResolvedValue({
      items: [
        {
          id: 1,
          targetType: 'THREAD',
          targetId: 101,
          reason: 'SPAM',
          detail: '重复广告',
          status: 'PENDING',
          createdAt: '2026-10-08T00:00:00Z',
          reporterId: 9,
        },
        {
          id: 2,
          targetType: 'POST',
          targetId: 202,
          reason: 'HARASSMENT',
          detail: '攻击性回复',
          status: 'PENDING',
          createdAt: '2026-10-08T00:00:00Z',
          reporterId: 10,
          threadId: 101,
        },
        {
          id: 3,
          targetType: 'POST',
          targetId: 303,
          reason: 'OTHER',
          detail: '',
          status: 'PENDING',
          createdAt: '2026-10-08T00:00:00Z',
          reporterId: 11,
        },
      ],
      page: 1,
      size: 20,
      total: 3,
    })
    const wrapper = createWrapper()

    await flushPromises()

    expect(wrapper.text()).toContain('帖子')
    expect(wrapper.text()).toContain('评论')
    expect(wrapper.text()).toContain('垃圾内容')
    expect(wrapper.text()).toContain('骚扰或辱骂')
    expect(wrapper.findAll('a[href="/threads/101"]').length).toBeGreaterThan(0)
    expect(wrapper.findAll('a[href="/threads/101#post-202"]').length).toBe(1)
    expect(wrapper.findAll('a[href="/threads/202"]').length).toBe(0)
    expect(wrapper.text()).toContain('未提供补充说明')
  })

  it('confirms, locks, resolves, and refreshes the queue', async () => {
    mocks.getAdminStatus.mockResolvedValue(true)
    mocks.listAdminReports
      .mockResolvedValueOnce({
        items: [{
          id: 17,
          targetType: 'THREAD',
          targetId: 700,
          reason: 'OTHER',
          detail: '请审核',
          status: 'PENDING',
          createdAt: '2026-10-08T00:00:00Z',
          reporterId: 9,
        }],
        page: 1,
        size: 20,
        total: 1,
      })
      .mockResolvedValueOnce(emptyPage)
    mocks.resolveAdminReport.mockResolvedValue(undefined)
    const wrapper = createWrapper()

    await flushPromises()
    const dismissButton = wrapper.findAll('button').find((button) => button.text() === '驳回举报')
    const hideButton = wrapper.findAll('button').find((button) => button.text() === '隐藏内容')
    expect(dismissButton).toBeDefined()
    expect(hideButton).toBeDefined()

    await dismissButton!.trigger('click')
    expect(window.confirm).toHaveBeenCalledWith('确认驳回举报 #17 吗？')
    expect(dismissButton!.attributes('disabled')).toBeDefined()
    expect(hideButton!.attributes('disabled')).toBeDefined()
    await flushPromises()

    expect(mocks.resolveAdminReport).toHaveBeenCalledWith(17, 'DISMISS')
    expect(mocks.listAdminReports).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[role="status"]').text()).toContain('驳回举报成功')
    expect(wrapper.text()).toContain('当前没有待处理举报')
  })

  it('offers a retryable list error after admin access succeeds', async () => {
    mocks.getAdminStatus.mockResolvedValue(true)
    mocks.listAdminReports.mockRejectedValueOnce(new Error('network failure')).mockResolvedValueOnce(emptyPage)
    const wrapper = createWrapper()

    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('举报列表加载失败')
    await alert.get('button').trigger('click')
    await flushPromises()

    expect(mocks.listAdminReports).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('当前没有待处理举报')
  })
})
