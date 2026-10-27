import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import SettingsNotificationsTab from '../SettingsNotificationsTab.vue'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { getMyNotificationSettings, updateMyNotificationSettings } from '@/api/notificationSettings'
import type { NotificationItem, PageResult } from '@/types'
import { signalNotificationChanges } from '@/composables/notificationSignals'

vi.mock('@/api/notifications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notifications')>()),
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/api/notificationSettings', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notificationSettings')>()),
  getMyNotificationSettings: vi.fn(),
  updateMyNotificationSettings: vi.fn(),
}))

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))

const listNotificationsMock = vi.mocked(listNotifications)
const markNotificationReadMock = vi.mocked(markNotificationRead)
const getMyNotificationSettingsMock = vi.mocked(getMyNotificationSettings)
const updateMyNotificationSettingsMock = vi.mocked(updateMyNotificationSettings)

const defaultSettings = {
  inApp: { reply: true, mention: true, like: true, follow: true, system: true as const },
  email: { enabled: false as const, frequency: 'instant' },
}

type NotificationPage = Awaited<ReturnType<typeof listNotifications>>
type NotificationQuery = Parameters<typeof listNotifications>[0]

interface ControlledRequest {
  id: number
  query: NotificationQuery
  startedInFlight: number
  ended: boolean
  resolve: (value: NotificationPage) => void
}

interface ControlledListReader {
  requests: ControlledRequest[]
  getInFlight: () => number
  getMaxInFlight: () => number
}

function result(id: number, title: string): PageResult<NotificationItem> {
  return {
    items: [{
      id,
      type: 'reply',
      title,
      content: `${title} 内容`,
      read: false,
      createdAt: '2026-10-01T00:00:00Z',
      link: `/threads/${id}`,
    }],
    page: 1,
    size: 20,
    total: 1,
  }
}

function mountNotificationsTab(): VueWrapper {
  return mount(SettingsNotificationsTab)
}

function controlNotificationReads(): ControlledListReader {
  const requests: ControlledRequest[] = []
  let inFlight = 0
  let maxInFlight = 0

  listNotificationsMock.mockImplementation((query = {}) => {
    let settle!: (value: NotificationPage) => void
    const request: ControlledRequest = {
      id: requests.length + 1,
      query: { ...query },
      startedInFlight: inFlight + 1,
      ended: false,
      resolve: (value) => settle(value),
    }
    inFlight += 1
    maxInFlight = Math.max(maxInFlight, inFlight)
    const promise = new Promise<NotificationPage>((resolve) => {
      settle = (value) => {
        if (!request.ended) {
          request.ended = true
          inFlight -= 1
        }
        resolve(value)
      }
    })
    requests.push(request)
    return promise
  })

  return {
    requests,
    getInFlight: () => inFlight,
    getMaxInFlight: () => maxInFlight,
  }
}

function evidenceQuery(query: NotificationQuery): Record<string, unknown> {
  return {
    type: query?.type === undefined ? '<undefined>' : query.type,
    unread: query?.unread,
    page: query?.page,
    size: query?.size,
  }
}

describe('SettingsNotificationsTab notification refresh burst measurement', () => {
  const wrappers: VueWrapper[] = []

  beforeEach(() => {
    vi.clearAllMocks()
    listNotificationsMock.mockReset()
    markNotificationReadMock.mockReset()
    getMyNotificationSettingsMock.mockReset()
    updateMyNotificationSettingsMock.mockReset()
    localStorage.setItem('accessToken', 'account-a')
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    getMyNotificationSettingsMock.mockResolvedValue(defaultSettings)
    updateMyNotificationSettingsMock.mockResolvedValue(defaultSettings)
    markNotificationReadMock.mockImplementation(async (id) => ({ id, read: true }))
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    localStorage.clear()
  })

  it('measures three same-query changed refreshes and stale-response suppression', async () => {
    const reader = controlNotificationReads()
    const wrapper = mountNotificationsTab()
    wrappers.push(wrapper)

    expect(reader.requests).toHaveLength(1)
    const initial = reader.requests[0]
    expect(initial.query).toEqual({ type: undefined, unread: false, page: 1, size: 20 })
    expect(initial.startedInFlight).toBe(1)
    expect(reader.getInFlight()).toBe(1)

    initial.resolve(result(1, '初始通知'))
    await flushPromises()
    expect(initial.ended).toBe(true)
    expect(reader.getInFlight()).toBe(0)
    expect(wrapper.text()).toContain('初始通知')

    // Each direct signal represents a changed event after the source's 100 ms
    // coalescing timer; no SSE transport or real-time delay is used here.
    signalNotificationChanges('changed')
    await Promise.resolve()
    signalNotificationChanges('changed')
    await Promise.resolve()
    signalNotificationChanges('changed')
    await Promise.resolve()

    expect(reader.requests.slice(1)).toHaveLength(1)
    expect(reader.getInFlight()).toBe(1)
    expect(reader.getMaxInFlight()).toBe(1)
    expect(localStorage.getItem('accessToken')).toBe('account-a')
    expect(markNotificationReadMock).not.toHaveBeenCalled()

    reader.requests[1].resolve(result(2, '队列中的旧返回'))
    await flushPromises()
    const changedRequests = reader.requests.slice(1)
    expect(changedRequests).toHaveLength(2)
    expect(changedRequests.map((request) => request.startedInFlight)).toEqual([1, 1])
    expect(changedRequests.map((request) => request.query)).toEqual([
      { type: undefined, unread: false, page: 1, size: 20 },
      { type: undefined, unread: false, page: 1, size: 20 },
    ])
    expect(reader.getInFlight()).toBe(1)
    expect(wrapper.text()).not.toContain('队列中的旧返回')
    changedRequests[1].resolve(result(4, '最新返回'))
    await flushPromises()
    expect(wrapper.text()).toContain('最新返回')
    expect(wrapper.text()).not.toContain('正在加载通知...')
    expect(reader.getInFlight()).toBe(0)

    console.log(`NOTIFICATION_REFRESH_BURST_RESULT ${JSON.stringify({
      sourceModel: 'direct changed signals after the useLiveNotifications 100 ms coalescing timer',
      initialMountListGetsIncluded: true,
      initialListGetCount: 1,
      changedSignalCount: 3,
      changedListGetCount: changedRequests.length,
      totalListGetCount: reader.requests.length,
      pendingAfterEachChangedSignal: [1, 1, 1],
      peakInFlight: reader.getMaxInFlight(),
      query: evidenceQuery(changedRequests[0].query),
      sessionTokenUnchanged: localStorage.getItem('accessToken') === 'account-a',
      markNotificationReadCallCount: markNotificationReadMock.mock.calls.length,
      followUpResponseRequestId: changedRequests[1].id,
      followUpResponseVisibleAfterInitialCompletion: wrapper.text().includes('最新返回'),
    })}`)
  })
})
