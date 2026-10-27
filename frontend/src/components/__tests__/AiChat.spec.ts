import { nextTick, reactive } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AiChat from '../AiChat.vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { AiChatMessage, ChatStreamHandle } from '@/api/ai'

vi.mock('@/api/ai', () => ({
  createChatStream: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: vi.fn(),
}))

const route = reactive({ path: '/settings' })
const useRouteMock = vi.mocked(useRoute)
const { createChatStream } = await import('@/api/ai')
const createChatStreamMock = vi.mocked(createChatStream)

type StreamRecord = {
  messages: AiChatMessage[]
  onMessage: (data: unknown) => void
  onError: (error: Error) => void
  onComplete: () => void
  handle: ChatStreamHandle
  resolveDone: () => void
}

let pinia: ReturnType<typeof createPinia>
const wrappers: Array<VueWrapper> = []
let streams: StreamRecord[] = []

function seedSession(token: string, id: number, username = `user-${id}`): void {
  const user = { id, username }
  localStorage.setItem('accessToken', token)
  localStorage.setItem('currentUserSession.v1', JSON.stringify({ accessToken: token, user }))
}

function mountChat(): VueWrapper {
  const wrapper = mount(AiChat, { attachTo: document.body, global: { plugins: [pinia] } })
  wrappers.push(wrapper)
  return wrapper
}

async function openChat(wrapper: VueWrapper): Promise<void> {
  await nextTick()
  if (!wrapper.find('[role="dialog"]').exists()) {
    await wrapper.get('button[aria-label="找小祥聊天"]').trigger('click')
  }
  await nextTick()
}

async function sendMessage(wrapper: VueWrapper, content: string): Promise<void> {
  await wrapper.get('input[aria-label="聊天消息"]').setValue(content)
  const sendButton = wrapper.findAll('button').find((button) => button.text() === '发送')
  await sendButton?.trigger('click')
  await nextTick()
}

function installDeferredStreamMock(): void {
  createChatStreamMock.mockImplementation((messages, onMessage, onError, onComplete) => {
    let doneResolve: () => void = () => undefined
    const handle = {
      cancel: vi.fn(),
      done: new Promise<void>((resolve) => { doneResolve = resolve }),
    }
    const record: StreamRecord = {
      messages,
      onMessage: onMessage || (() => undefined),
      onError: onError || (() => undefined),
      onComplete: onComplete || (() => undefined),
      handle,
      resolveDone: () => doneResolve(),
    }
    streams.push(record)
    return handle
  })
}

describe('AiChat settings placement and session ownership', () => {
  beforeEach(() => {
    route.path = '/settings'
    useRouteMock.mockReturnValue(route as never)
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    streams = []
    createChatStreamMock.mockReset()
  })

  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('adds a mobile-only settings hide class while retaining the launcher elsewhere', async () => {
    const wrapper = mountChat()

    expect(wrapper.classes()).toContain('settings-mobile-hidden')

    route.path = '/discover'
    await nextTick()

    expect(wrapper.classes()).not.toContain('settings-mobile-hidden')
  })

  it('opens from the account entry and restores focus when closed', async () => {
    seedSession('focus-token', 1)
    route.path = '/me'
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const wrapper = mountChat()
    window.dispatchEvent(new CustomEvent('open-ai-chat'))
    await nextTick()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(wrapper.get('input[aria-label="聊天消息"]').element).toBe(document.activeElement)
    await wrapper.get('button[aria-label="关闭聊天"]').trigger('click')
    expect(document.activeElement).toBe(opener)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    opener.remove()
  })

  it('keeps guest chat login-only and never starts a stream', async () => {
    const login = vi.fn()
    window.addEventListener('open-login-modal', login)
    const wrapper = mountChat()
    await openChat(wrapper)

    expect(wrapper.text()).toContain('登录后才能和小祥聊天')
    await wrapper.get('button[aria-label="登录后聊天"]').trigger('click')

    expect(login).toHaveBeenCalledOnce()
    expect(createChatStreamMock).not.toHaveBeenCalled()
    window.removeEventListener('open-login-modal', login)
  })

  it('preserves same-actor history across close and reopen, then resets on logout/login', async () => {
    seedSession('actor-a', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'A message')
    streams[0].onMessage({ content: 'A reply' })
    streams[0].onComplete()
    await nextTick()

    await wrapper.get('button[aria-label="关闭聊天"]').trigger('click')
    await openChat(wrapper)
    expect(wrapper.text()).toContain('A message')
    expect(wrapper.text()).toContain('A reply')

    const auth = useAuthStore(pinia)
    auth.clearAuthState()
    await nextTick()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

    auth.applyAuthState({ accessToken: 'actor-b', user: { id: 2, username: 'user-2' } })
    await openChat(wrapper)
    expect(wrapper.text()).not.toContain('A message')
    expect(wrapper.text()).not.toContain('A reply')
    expect(wrapper.text()).toContain('……你是谁？如果是无关紧要的事，请不要打扰我。')
  })

  it('rejects storage-only token drift before sending', async () => {
    seedSession('actor-a', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    localStorage.setItem('accessToken', 'actor-b')
    await sendMessage(wrapper, 'must not cross sessions')

    expect(createChatStreamMock).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('must not cross sessions')
  })

  it('syncs storage-only drift before opening from the account entry', async () => {
    seedSession('actor-a', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'account history')
    streams[0].onMessage({ content: 'account reply' })
    streams[0].onComplete()
    await nextTick()

    await wrapper.get('button[aria-label="关闭聊天"]').trigger('click')
    localStorage.setItem('accessToken', 'actor-b')
    window.dispatchEvent(new CustomEvent('open-ai-chat'))
    await nextTick()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('account history')
    expect(wrapper.text()).not.toContain('account reply')
    expect(wrapper.text()).toContain('……你是谁？如果是无关紧要的事，请不要打扰我。')
  })

  it('syncs storage-only drift before opening from the floating launcher', async () => {
    seedSession('actor-a', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'launcher history')
    streams[0].onMessage({ content: 'launcher reply' })
    streams[0].onComplete()
    await nextTick()

    await wrapper.get('button[aria-label="关闭聊天"]').trigger('click')
    localStorage.setItem('accessToken', 'actor-b')
    await wrapper.get('button[aria-label="找小祥聊天"]').trigger('click')
    await nextTick()

    expect(wrapper.text()).not.toContain('launcher history')
    expect(wrapper.text()).not.toContain('launcher reply')
    expect(wrapper.text()).toContain('……你是谁？如果是无关紧要的事，请不要打扰我。')
  })

  it('preserves same-token unknown-to-known hydration without an auth-generation reset', async () => {
    localStorage.setItem('accessToken', 'hydrate-token')
    installDeferredStreamMock()
    const wrapper = mountChat()
    const auth = useAuthStore(pinia)
    await openChat(wrapper)
    await sendMessage(wrapper, 'hydrate me')
    streams[0].onMessage({ content: 'still mine' })

    auth.invalidateAuthOperations()
    expect(auth.hydrateCurrentUser({ id: 7, username: 'hydrated' }, 'hydrate-token')).toBe(true)
    await nextTick()

    expect(wrapper.text()).toContain('hydrate me')
    expect(wrapper.text()).toContain('still mine')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
  })

  it('invalidates nested owner changes and A-to-B-to-A transitions', async () => {
    seedSession('stable-token', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    const auth = useAuthStore(pinia)
    await openChat(wrapper)
    await sendMessage(wrapper, 'owner scoped')

    auth.user!.id = 2
    await nextTick()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(streams[0].handle.cancel).toHaveBeenCalledOnce()

    auth.user!.id = 1
    await openChat(wrapper)
    expect(wrapper.text()).not.toContain('owner scoped')
  })

  it('sends only the new actor history and rejects old stream callbacks after an account switch', async () => {
    seedSession('actor-a', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'A private message')
    streams[0].onMessage({ content: 'A private reply' })
    const auth = useAuthStore(pinia)
    auth.applyAuthState({ accessToken: 'actor-b', user: { id: 2, username: 'B' } })
    await openChat(wrapper)
    await sendMessage(wrapper, 'B new message')
    streams[0].onMessage({ content: 'A late reply' })
    streams[0].onError(new Error('A late failure'))
    streams[0].onComplete()
    streams[1].onMessage({ content: 'B new reply' })
    await nextTick()

    expect(JSON.stringify(streams[1].messages)).not.toContain('A private')
    expect(wrapper.text()).not.toContain('A private')
    expect(wrapper.text()).not.toContain('A late')
    expect(wrapper.text()).toContain('B new reply')
    expect(wrapper.findAll('button').some(button => button.text() === '停止')).toBe(true)
  })

  it('clears active history when storage drift is first observed by a stream callback', async () => {
    seedSession('actor-a', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'A private message')
    localStorage.setItem('accessToken', 'actor-b')
    streams[0].onMessage({ content: 'must not appear' })
    await nextTick()

    expect(streams[0].handle.cancel).toHaveBeenCalledOnce()
    await openChat(wrapper)
    expect(wrapper.text()).not.toContain('A private message')
    expect(wrapper.text()).not.toContain('must not appear')
    expect(wrapper.text()).toContain('登录后才能和小祥聊天')
  })

  it('preserves the stream when same-owner display metadata changes', async () => {
    seedSession('actor-a', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'keep my chat')
    useAuthStore(pinia).user!.username = 'new display name'
    streams[0].onMessage({ content: 'still current' })
    await nextTick()

    expect(streams[0].handle.cancel).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('keep my chat')
    expect(wrapper.text()).toContain('still current')
  })

  it('invalidates before close cancellation so late callbacks cannot append', async () => {
    seedSession('cancel-token', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'keep this user message')
    vi.mocked(streams[0].handle.cancel).mockImplementation(() => {
      streams[0].onMessage({ content: 'late reply' })
      streams[0].onComplete()
    })

    await wrapper.get('button[aria-label="关闭聊天"]').trigger('click')
    await openChat(wrapper)
    expect(wrapper.text()).toContain('keep this user message')
    expect(wrapper.text()).not.toContain('late reply')
  })

  it('does not sync auth context from a synchronous callback during unmount cancellation', async () => {
    seedSession('unmount-cancel-token', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'unmount cancel')
    const getItemSpy = vi.spyOn(Storage.prototype, 'getItem').mockClear()
    vi.mocked(streams[0].handle.cancel).mockImplementation(() => {
      streams[0].onMessage({ content: 'late unmount reply' })
      streams[0].onComplete()
    })

    wrapper.unmount()

    expect(getItemSpy).not.toHaveBeenCalled()
  })

  it('ignores asynchronous callbacks after unmount without syncing auth context', async () => {
    seedSession('unmount-async-token', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'unmount async')
    const getItemSpy = vi.spyOn(Storage.prototype, 'getItem').mockClear()
    wrapper.unmount()

    await Promise.resolve().then(() => {
      streams[0].onMessage({ content: 'late async reply' })
      streams[0].onComplete()
    })

    expect(getItemSpy).not.toHaveBeenCalled()
  })

  it('keeps a new request isolated from callbacks of a stopped request', async () => {
    seedSession('request-token', 1)
    installDeferredStreamMock()
    const wrapper = mountChat()
    await openChat(wrapper)
    await sendMessage(wrapper, 'first')
    const stopButton = wrapper.findAll('button').find((button) => button.text() === '停止')
    await stopButton?.trigger('click')
    await sendMessage(wrapper, 'second')

    streams[0].onMessage({ content: 'old reply' })
    streams[0].onComplete()
    await nextTick()
    expect(wrapper.text()).toContain('second')
    expect(wrapper.text()).not.toContain('old reply')
    expect(wrapper.findAll('button').some((button) => button.text() === '停止')).toBe(true)
  })

  it('handles synchronous stream callbacks and synchronous creation failures', async () => {
    seedSession('sync-token', 1)
    const wrapper = mountChat()
    await openChat(wrapper)
    createChatStreamMock.mockImplementationOnce((messages, onMessage, _onError, onComplete) => {
      onMessage?.({ content: 'sync reply' })
      onComplete?.()
      return { cancel: vi.fn(), done: Promise.resolve() }
    })
    await sendMessage(wrapper, 'sync')
    expect(wrapper.text()).toContain('sync reply')

    createChatStreamMock.mockImplementationOnce(() => {
      throw new Error('synchronous transport failure')
    })
    await sendMessage(wrapper, 'fails safely')
    expect(wrapper.text()).toContain('呜呜，连接出错了，请稍后再试...')
  })
})
