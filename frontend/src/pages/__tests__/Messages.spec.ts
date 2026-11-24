import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Messages from '@/pages/Messages.vue'
import * as api from '@/api/chat'
import type { History, Preview } from '@/api/chat'
import { connectChatRealtime } from '@/pages/messages/realtime'

const session = vi.hoisted(() => ({
  auth: { token: 'signed-in-account', user: { id: 7 }, isLoggedIn: true },
  route: { query: {} as { peer?: string } },
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => session.auth }))
vi.mock('@/utils/authStorage', () => ({ getStoredAccessToken: () => session.auth.token }))
vi.mock('vue-router', () => ({ useRoute: () => session.route, useRouter: () => ({ replace: vi.fn() }) }))
vi.mock('@/api/chat', () => ({
  conversationList: vi.fn(),
  direct: vi.fn(),
  history: vi.fn(),
  groupMembership: vi.fn(),
  read: vi.fn().mockResolvedValue(undefined),
  sendDirect: vi.fn(),
  respond: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/components/ReportDialog.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/pages/messages/realtime', () => ({ connectChatRealtime: vi.fn() }))

const quota = { remaining: 10, limit: 10, resetAt: '2026-11-24T16:00:00Z' }
const newDirect = { id: null, peer: { id: 42, name: '小林' }, state: 'NEW' as const, mutual: false,
  canSend: true, canDecide: false, blockedByMe: false, quota }
const message = { id: 9, seq: 1, sender: { id: 7, name: '当前用户' }, content: '你好', createdAt: '2026-11-24T00:00:00Z' }

function page(items: Preview[] = []) {
  return { page: 1, size: 30, total: items.length, items }
}

function captureRealtimeChange() {
  let signal: () => void = () => { throw new Error('Realtime connector did not start') }
  vi.mocked(connectChatRealtime).mockImplementationOnce((_token, _current, onReady, onChange) => {
    onReady(true)
    signal = onChange
    return vi.fn()
  })
  return () => signal()
}

function render() {
  return mount(Messages, { global: { stubs: { RouterLink: true, ChatFriendPicker: true } } })
}

describe('消息中心', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    session.route.query = {}
    vi.mocked(api.conversationList).mockResolvedValue(page())
    vi.mocked(api.groupMembership).mockResolvedValue({ state: 'JOINED', ownerId: 42 })
    vi.mocked(api.history).mockReset().mockResolvedValue({ items: [], hasMore: false, visibleFromSeq: 1, revision: 0 })
  })

  it('reuses the send identifier after a lost response', async () => {
    session.route.query = { peer: '42' }
    vi.mocked(api.direct).mockResolvedValueOnce(newDirect).mockResolvedValue({
      ...newDirect, id: 4, state: 'PENDING', canSend: false, quota: { ...quota, remaining: 9 },
    })
    vi.mocked(api.sendDirect).mockRejectedValueOnce(new Error('disconnected')).mockResolvedValueOnce({
      conversationId: 4, message, state: 'PENDING', quota: { ...quota, remaining: 9 },
    })
    vi.mocked(api.history).mockResolvedValue({ items: [message], hasMore: false, visibleFromSeq: 1, revision: 0 })
    const wrapper = render()
    try {
      await flushPromises()
      await wrapper.get('textarea').setValue('你好')
      await wrapper.get('.messages__composer form').trigger('submit')
      await flushPromises()
      expect(wrapper.text()).toContain('发送结果未确认')
      await wrapper.get('.messages__composer form').trigger('submit')
      await flushPromises()
      expect(api.sendDirect).toHaveBeenCalledTimes(2)
      expect(vi.mocked(api.sendDirect).mock.calls[0]).toEqual(vi.mocked(api.sendDirect).mock.calls[1])
      expect(wrapper.findAll('.messages__bubble')).toHaveLength(1)
      expect(wrapper.text()).toContain('等待对方接受消息请求')
    } finally { wrapper.unmount() }
  })

  it('opens group history only after an invitation is accepted', async () => {
    const invite: Preview = { id: 4, peerId: null, title: '摄影', state: 'ACTIVE', ownerId: 42,
      membership: 'INVITED', preview: null, unread: false }
    vi.mocked(api.conversationList).mockImplementation(async bucket => page(bucket === 'requests' ? [invite] : []))
    vi.mocked(api.groupMembership).mockResolvedValueOnce({ state: 'INVITED', ownerId: 42 })
    const wrapper = render()
    try {
      await flushPromises()
      await wrapper.findAll('.messages__tab')[2]!.trigger('click')
      await flushPromises()
      await wrapper.get('.messages__entry').trigger('click')
      await flushPromises()
      expect(api.history).not.toHaveBeenCalled()
      const join = wrapper.findAll('button').find(button => button.text() === '加入群聊')!
      await join.trigger('click')
      await flushPromises()
      expect(api.respond).toHaveBeenCalledWith(4, true)
      expect(api.history).toHaveBeenCalledWith(4)
      expect(wrapper.find('textarea').exists()).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('polls while disconnected and loads all 31 pending messages in order after reconnecting', async () => {
    session.route.query = { peer: '42' }
    vi.mocked(api.direct).mockResolvedValue({ ...newDirect, id: 4, state: 'ACTIVE' })
    const fallback = { ...message, id: 10, seq: 1, content: 'Received by polling' }
    const backlog = Array.from({ length: 31 }, (_, index) => ({
      ...message, id: 11 + index, seq: 2 + index, content: `Pending message ${index + 1}`,
    }))
    vi.mocked(api.history)
      .mockResolvedValueOnce({ items: [], hasMore: false, visibleFromSeq: 1, revision: 0 })
      .mockResolvedValueOnce({ items: [fallback], hasMore: false, visibleFromSeq: 1, revision: 0 })
      .mockResolvedValueOnce({ items: backlog.slice(0, 30), hasMore: true, visibleFromSeq: 1, revision: 0 })
      .mockResolvedValueOnce({ items: backlog.slice(30), hasMore: false, visibleFromSeq: 1, revision: 0 })
    let reconnect: () => void = () => { throw new Error('Realtime connector did not start') }
    const disconnect = vi.fn()
    vi.mocked(connectChatRealtime).mockImplementationOnce((_token, _current, onReady, onChange) => {
      onReady(false)
      reconnect = () => { onReady(true); onChange() }
      return disconnect
    })
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const wrapper = render()
    try {
      await flushPromises()
      await vi.advanceTimersByTimeAsync(3000)
      expect(wrapper.findAll('.messages__text').map(item => item.text())).toEqual([fallback.content])

      reconnect()
      await vi.waitFor(() => expect(wrapper.findAll('.messages__text').map(item => item.text()))
        .toEqual([fallback, ...backlog].map(item => item.content)))
      expect(api.history).toHaveBeenNthCalledWith(2, 4, { afterSeq: 0 })
      expect(api.history).toHaveBeenNthCalledWith(3, 4, { afterSeq: 1 })
      expect(api.history).toHaveBeenNthCalledWith(4, 4, { afterSeq: 31 })
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
    expect(disconnect).toHaveBeenCalledOnce()
  })

  it('replaces cached history after a member rejoins elsewhere', async () => {
    const group: Preview = { id: 4, peerId: null, title: 'Group', state: 'ACTIVE', ownerId: 42,
      membership: 'JOINED', preview: null, unread: false }
    vi.mocked(api.conversationList).mockResolvedValue(page([group]))
    const currentMessage = { ...message, id: 12, seq: 4, content: 'After rejoin' }
    vi.mocked(api.history)
      .mockResolvedValueOnce({ items: [{ ...message, seq: 2, content: 'Previous membership' }], hasMore: false,
        visibleFromSeq: 2, revision: 0 })
      .mockResolvedValueOnce({ items: [currentMessage], hasMore: false, visibleFromSeq: 4, revision: 0 })
      .mockResolvedValueOnce({ items: [currentMessage], hasMore: false, visibleFromSeq: 4, revision: 0 })
    const signal = captureRealtimeChange()
    const wrapper = render()
    try {
      await flushPromises()
      await wrapper.get('.messages__entry').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('Previous membership')

      signal()
      await vi.waitFor(() => expect(wrapper.text()).toContain('After rejoin'))
      expect(wrapper.text()).not.toContain('Previous membership')
      expect(api.history).toHaveBeenNthCalledWith(2, 4, { afterSeq: 2 })
      expect(api.history).toHaveBeenNthCalledWith(3, 4)
    } finally { wrapper.unmount() }
  })

  it('keeps the realtime refresh queued when the page becomes visible during a history request', async () => {
    session.route.query = { peer: '42' }
    vi.mocked(api.direct).mockResolvedValue({ ...newDirect, id: 4, state: 'ACTIVE' })
    const initial: History = { items: [{ ...message, id: 10, seq: 2 }], hasMore: true, visibleFromSeq: 1, revision: 0 }
    const incoming = { ...message, id: 11, seq: 3, content: 'Received while hidden' }
    let finishOlder: (history: History) => void = () => { throw new Error('Older history was not requested') }
    vi.mocked(api.history)
      .mockResolvedValueOnce(initial)
      .mockImplementationOnce(() => new Promise(resolve => { finishOlder = resolve }))
      .mockResolvedValueOnce({ ...initial, items: [incoming], hasMore: false })
    const hidden = vi.spyOn(document, 'hidden', 'get')
    hidden.mockReturnValue(false)
    const signal = captureRealtimeChange()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const wrapper = render()
    try {
      await flushPromises()
      await wrapper.get('.messages__history > button').trigger('click')
      expect(api.history).toHaveBeenLastCalledWith(4, { beforeSeq: 2 })

      hidden.mockReturnValue(true)
      signal()
      hidden.mockReturnValue(false)
      document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(100)
      expect(api.history).toHaveBeenCalledTimes(2)

      finishOlder({ ...initial, items: [message], hasMore: false })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(100)
      expect(wrapper.text()).toContain(incoming.content)
      expect(api.history).toHaveBeenLastCalledWith(4, { afterSeq: 2 })
    } finally {
      wrapper.unmount()
      hidden.mockRestore()
      vi.useRealTimers()
    }
  })

  it('refreshes the sent message after an older poll finishes without a new realtime signal', async () => {
    session.route.query = { peer: '42' }
    vi.mocked(api.direct).mockResolvedValue({ ...newDirect, id: 4, state: 'ACTIVE' })
    const initial: History = { items: [message], hasMore: false, visibleFromSeq: 1, revision: 0 }
    const sent = { ...message, id: 10, seq: 2, content: 'Sent during a poll' }
    let finishPoll: (history: History) => void = () => { throw new Error('The first poll did not start') }
    vi.mocked(api.history)
      .mockResolvedValueOnce(initial)
      .mockImplementationOnce(() => new Promise(resolve => { finishPoll = resolve }))
      .mockResolvedValueOnce({ ...initial, items: [sent] })
    vi.mocked(api.sendDirect).mockResolvedValue({ conversationId: 4, message: sent, state: 'ACTIVE', quota })
    const signal = captureRealtimeChange()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const wrapper = render()
    try {
      await flushPromises()
      signal()
      await vi.advanceTimersByTimeAsync(100)
      expect(api.history).toHaveBeenCalledTimes(2)
      await wrapper.get('textarea').setValue(sent.content)
      await wrapper.get('.messages__composer form').trigger('submit')
      await flushPromises()
      expect(api.sendDirect).toHaveBeenCalledOnce()
      await vi.advanceTimersByTimeAsync(100)
      expect(api.history).toHaveBeenCalledTimes(2)

      finishPoll({ ...initial, items: [] })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(100)
      expect(wrapper.text()).toContain(sent.content)
      expect(api.history).toHaveBeenNthCalledWith(3, 4, { afterSeq: 1 })
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('discards loaded moderation text outside the newest history page', async () => {
    session.route.query = { peer: '42' }
    vi.mocked(api.direct).mockResolvedValue({ ...newDirect, id: 4, state: 'ACTIVE' })
    const oldMessage = { ...message, content: 'Previously loaded body' }
    const recent = Array.from({ length: 30 }, (_, index) => ({ ...message, id: 20 + index, seq: 2 + index }))
    vi.mocked(api.history)
      .mockResolvedValueOnce({ items: recent, hasMore: true, visibleFromSeq: 1, revision: 0 })
      .mockResolvedValueOnce({ items: [oldMessage], hasMore: false, visibleFromSeq: 1, revision: 0 })
      .mockResolvedValueOnce({ items: [], hasMore: false, visibleFromSeq: 1, revision: 1 })
      .mockResolvedValueOnce({ items: recent, hasMore: true, visibleFromSeq: 1, revision: 1 })
      .mockResolvedValueOnce({ items: [{ ...oldMessage, content: null }], hasMore: false, visibleFromSeq: 1, revision: 1 })
    const signal = captureRealtimeChange()
    const wrapper = render()
    try {
      await flushPromises()
      await wrapper.get('.messages__history > button').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain(oldMessage.content)

      signal()
      await vi.waitFor(() => expect(api.history).toHaveBeenCalledTimes(4))
      expect(wrapper.text()).not.toContain(oldMessage.content)
      await vi.waitFor(() => expect(wrapper.get('.messages__history > button').attributes('disabled')).toBeUndefined())
      await wrapper.get('.messages__history > button').trigger('click')
      await flushPromises()
      expect(wrapper.findAll('.messages__bubble')).toHaveLength(31)
      expect(wrapper.text()).not.toContain(oldMessage.content)
    } finally { wrapper.unmount() }
  })

  it('removes the accept action when the open group invitation is revoked', async () => {
    const invite: Preview = { id: 4, peerId: null, title: 'Group invitation', state: 'ACTIVE', ownerId: 42,
      membership: 'INVITED', preview: null, unread: false }
    vi.mocked(api.conversationList).mockImplementation(async bucket => page(bucket === 'requests' ? [invite] : []))
    const missing = Object.assign(new Error('Not found'), { isAxiosError: true, response: { status: 404 } })
    vi.mocked(api.groupMembership).mockResolvedValueOnce({ state: 'INVITED', ownerId: 42 }).mockRejectedValue(missing)
    const signal = captureRealtimeChange()
    const wrapper = render()
    const accept = () => wrapper.findAll('button').find(button => button.text() === '加入群聊')
    try {
      await flushPromises()
      await wrapper.findAll('.messages__tab')[2]!.trigger('click')
      await flushPromises()
      await wrapper.get('.messages__entry').trigger('click')
      await flushPromises()
      expect(accept()).toBeDefined()

      vi.mocked(api.conversationList).mockResolvedValue(page())
      signal()
      await vi.waitFor(() => expect(accept()).toBeUndefined())
      expect(wrapper.text()).toContain('该会话已不可访问')
      expect(api.history).not.toHaveBeenCalled()

      vi.mocked(api.groupMembership).mockResolvedValue({ state: 'INVITED', ownerId: 42 })
      signal()
      await vi.waitFor(() => expect(accept()).toBeDefined())
      expect(api.history).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('refreshes both the inbox and the badge once after multiple realtime signals during a fetch', async () => {
    const incoming: Preview = { id: 4, peerId: 42, title: 'New contact', state: 'ACTIVE', ownerId: null,
      membership: 'JOINED', preview: null, unread: true }
    const requested: Preview = { ...incoming, id: 5, state: 'PENDING' }
    const releases = new Map<string, (result: ReturnType<typeof page>) => void>()
    const list = vi.mocked(api.conversationList)
    list.mockImplementation(bucket => {
      if (releases.has(bucket)) return Promise.resolve(page([bucket === 'messages' ? incoming : requested]))
      return new Promise(resolve => { releases.set(bucket, resolve) })
    })
    const signal = captureRealtimeChange()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const wrapper = render()
    try {
      expect(api.conversationList).toHaveBeenCalledTimes(2)
      for (let i = 0; i < 2; i++) {
        signal()
        await vi.advanceTimersByTimeAsync(100)
      }
      expect(list.mock.calls.filter(([bucket]) => bucket === 'messages')).toHaveLength(1)
      expect(list.mock.calls.filter(([bucket]) => bucket === 'requests')).toHaveLength(1)

      releases.get('messages')!(page())
      releases.get('requests')!(page())
      await flushPromises()
      expect(list.mock.calls.filter(([bucket]) => bucket === 'messages')).toHaveLength(2)
      expect(list.mock.calls.filter(([bucket]) => bucket === 'requests')).toHaveLength(2)
      expect(wrapper.get('.messages__entry').text()).toContain(incoming.title)
      expect(wrapper.get('.messages__count').text()).toBe('1')
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('does not overwrite a fresh request count with a response held across tab changes', async () => {
    const pending: Preview = { id: 4, peerId: 42, title: 'Pending request', state: 'PENDING', ownerId: null,
      membership: 'JOINED', preview: null, unread: true }
    let releaseCount: ((result: ReturnType<typeof page>) => void) | undefined
    vi.mocked(api.conversationList).mockImplementation(bucket => {
      if (bucket === 'messages') return Promise.resolve(page())
      if (releaseCount) return Promise.resolve(page([pending]))
      return new Promise(resolve => { releaseCount = resolve })
    })
    const wrapper = render()
    try {
      await flushPromises()
      await wrapper.findAll('.messages__tab')[2]!.trigger('click')
      await flushPromises()
      expect(wrapper.get('.messages__count').text()).toBe('1')
      await wrapper.findAll('.messages__tab')[0]!.trigger('click')
      await flushPromises()

      releaseCount!(page())
      await flushPromises()
      expect(wrapper.get('.messages__count').text()).toBe('1')
    } finally { wrapper.unmount() }
  })

  it('refreshes the inbox and request count during a realtime outage', async () => {
    const incoming: Preview = { id: 4, peerId: 42, title: 'New contact', state: 'ACTIVE', ownerId: null,
      membership: 'JOINED', preview: 'Incoming message', unread: true }
    let setReady: (ready: boolean) => void = () => { throw new Error('Realtime connector did not start') }
    vi.mocked(connectChatRealtime).mockImplementationOnce((_token, _current, onReady) => {
      setReady = onReady
      onReady(true)
      return vi.fn()
    })
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const wrapper = render()
    try {
      await flushPromises()
      let releaseInbox: (result: ReturnType<typeof page>) => void = () => { throw new Error('Inbox was not requested') }
      vi.mocked(api.conversationList).mockImplementation(async bucket => bucket === 'messages'
        ? new Promise(resolve => { releaseInbox = resolve })
        : page([{ ...incoming, id: 5, state: 'PENDING' }]))
      await vi.advanceTimersByTimeAsync(3000)
      expect(api.conversationList).toHaveBeenCalledTimes(2)

      setReady(false)
      await vi.advanceTimersByTimeAsync(3000)
      expect(wrapper.get('.messages__count').text()).toBe('1')
      await vi.advanceTimersByTimeAsync(3000)
      expect(vi.mocked(api.conversationList).mock.calls.filter(([bucket]) => bucket === 'messages')).toHaveLength(2)
      releaseInbox(page([incoming]))
      await flushPromises()
      expect(wrapper.get('.messages__entry').text()).toContain('Incoming message')
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })
})
