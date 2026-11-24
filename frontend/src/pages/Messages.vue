<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as chat from '@/api/chat'
import FriendRequestActions from '@/components/FriendRequestActions.vue'
import ReportDialog from '@/components/ReportDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken } from '@/utils/authStorage'
import type { User } from '@/types'
import ChatFriendPicker from './messages/ChatFriendPicker.vue'
import { connectChatRealtime } from './messages/realtime'

type Tab = chat.Bucket | 'friends' | 'friendRequests' | 'settings'
type Target = { peer: number } | { group: chat.Preview }
type RefreshRequest = { refreshQueued: boolean }

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const userId = computed(() => Number(auth.user?.id ?? auth.user?.userId))
const tabs: Array<{ id: Tab; label: string }> = [
  { id: 'messages', label: '会话' }, { id: 'friends', label: '好友' },
  { id: 'requests', label: '请求' }, { id: 'friendRequests', label: '好友申请' }, { id: 'settings', label: '设置' },
]

const tab = ref<Tab>('messages')
const page = ref(1)
const total = ref(0)
const requestCount = ref(0)
const previews = ref<chat.Preview[]>([])
const friends = ref<User[]>([])
const blocked = ref<chat.Person[]>([])
const allowRequests = ref<boolean | null>(null)
const listLoading = ref(false)
const listError = ref('')
const target = ref<Target | null>(null)
const direct = ref<chat.Direct | null>(null)
const group = computed(() => target.value && 'group' in target.value ? target.value.group : null)
const roomId = computed(() => group.value?.id ?? direct.value?.id ?? null)
const title = computed(() => group.value?.title ?? direct.value?.peer.name ?? '私信')
const owner = computed(() => group.value?.ownerId === userId.value)
const invited = computed(() => group.value?.membership === 'INVITED')
const messages = ref<chat.Message[]>([])
const now = ref(Date.now())
const hasOlder = ref(false)
const roomLoading = ref(false)
const loadingOlder = ref(false)
const polling = ref(false)
const roomError = ref('')
const actionError = ref('')
const busy = ref(false)
const draft = ref('')
const reportId = ref(0)
const reportOpen = ref(false)
const scrollbox = ref<HTMLElement | null>(null)
const newMessages = ref(false)
const creating = ref(false)
const groupTitle = ref('')
const invitees = ref<number[]>([])
const managerOpen = ref(false)
const members = ref<chat.Member[]>([])
const selectedInvite = ref<number[]>([])

let selection = 0
let mutation = 0
let listRequest: RefreshRequest | undefined
let requestCountRequest: RefreshRequest | undefined
let lastRead = 0
let mounted = false
let historyVersion: Pick<chat.History, 'visibleFromSeq' | 'revision'> | undefined
let listPoll = 0
let timer: number | undefined
let stopRealtime: (() => void) | undefined
let realtimeReady = false
let realtimeDirty = false
let realtimeTimer: number | undefined
let retry: chat.Send | null = null

function sameAccount(token: string) {
  return mounted && !!token && auth.token === token && getStoredAccessToken() === token
}

function sameRoom(generation: number, token: string) {
  return generation === selection && sameAccount(token)
}

function errorText(error: unknown, fallback: string) {
  if (!isAxiosError(error)) return fallback
  const data = error.response?.data as { message?: string; quota?: chat.Quota } | undefined
  return data?.quota ? `${data.message || fallback}，${resetTime(data.quota)}恢复` : data?.message || fallback
}

function resetTime(quota: chat.Quota) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(quota.resetAt))
}

async function loadList(mode: 'foreground' | 'poll' | 'invalidate' = 'foreground') {
  const token = auth.token
  if (!sameAccount(token)) return
  if (mode !== 'foreground' && listRequest) {
    if (mode === 'invalidate') listRequest.refreshQueued = true
    return
  }
  const request = { refreshQueued: false }
  listRequest = request
  const bucket = tab.value
  const pageNumber = page.value
  const current = () => sameAccount(token) && listRequest === request && tab.value === bucket && page.value === pageNumber
  if (mode === 'foreground') listLoading.value = true
  try {
    if (bucket === 'settings') {
      const [allow, people] = await Promise.all([chat.preferences(), chat.blocks()])
      if (current()) {
        blocked.value = people
        allowRequests.value = allow
      }
    } else if (bucket === 'friends' || bucket === 'friendRequests') {
      const result = bucket === 'friends' ? await chat.friends(pageNumber) : await chat.incomingFriendRequests(pageNumber)
      if (current()) { friends.value = result.items; total.value = result.total }
    } else {
      if (bucket === 'requests') requestCountRequest = undefined
      const result = await chat.conversationList(bucket, pageNumber)
      if (current()) {
        previews.value = result.items
        total.value = result.total
        if (bucket === 'requests') requestCount.value = result.total
      }
    }
    if (current()) listError.value = ''
  } catch (error) {
    if (current() && mode === 'foreground') listError.value = errorText(error, '列表加载失败')
  } finally {
    if (current()) {
      listRequest = undefined
      listLoading.value = false
      if (request.refreshQueued) void loadList('poll')
    }
  }
}

async function loadRequestCount(invalidate = false) {
  const token = auth.token
  if (!sameAccount(token) || tab.value === 'requests') return
  if (requestCountRequest) {
    if (invalidate) requestCountRequest.refreshQueued = true
    return
  }
  const request = { refreshQueued: false }
  requestCountRequest = request
  try {
    const result = await chat.conversationList('requests', 1)
    if (sameAccount(token) && requestCountRequest === request) requestCount.value = result.total
  } catch {
    // The requests tab can still be refreshed manually.
  } finally {
    if (requestCountRequest === request) {
      requestCountRequest = undefined
      if (request.refreshQueued && sameAccount(token)) void loadRequestCount()
    }
  }
}

function openPreview(item: chat.Preview) {
  select(item.peerId ? { peer: item.peerId } : { group: { ...item } })
}

function select(value: Target | null) {
  selection++
  target.value = value
  direct.value = null
  creating.value = false
  clearHistory()
  roomLoading.value = false
  loadingOlder.value = false
  busy.value = false
  draft.value = ''
  retry = null
  managerOpen.value = false
  members.value = []
  selectedInvite.value = []
  actionError.value = ''
  roomError.value = ''
  if (value) void loadRoom()
}

function clearHistory() {
  historyVersion = undefined
  messages.value = []
  hasOlder.value = false
  lastRead = 0
  newMessages.value = false
  reportOpen.value = false
}

function back() {
  select(null)
  if (route.query.peer) void router.replace({ name: 'messages' })
}

async function refreshAccess(active: Target, current: () => boolean) {
  if ('peer' in active) {
    const result = await chat.direct(active.peer)
    if (current()) direct.value = result
    return
  }
  const result = await chat.groupMembership(active.group.id)
  if (!current()) return
  if (active.group.membership !== result.state) {
    clearHistory()
    managerOpen.value = false
    members.value = []
  }
  active.group.membership = result.state
  active.group.ownerId = result.ownerId
}

async function loadLatestHistory(id: number, current: () => boolean) {
  const result = await chat.history(id)
  if (!current()) return
  historyVersion = { visibleFromSeq: result.visibleFromSeq, revision: result.revision }
  messages.value = result.items
  hasOlder.value = result.hasMore
  roomError.value = ''
  if (result.items.length) await jumpToLatest()
}

function sameHistoryVersion(history: chat.History) {
  return historyVersion?.revision === history.revision && historyVersion?.visibleFromSeq === history.visibleFromSeq
}

function markRoomUnavailable() {
  const wasUnavailable = roomError.value === '该会话已不可访问'
  clearHistory()
  managerOpen.value = false
  members.value = []
  roomError.value = '该会话已不可访问'
  if (!wasUnavailable) void loadList()
}

async function loadRoom() {
  const generation = selection
  const token = auth.token
  const active = target.value
  if (!active || !sameAccount(token)) return
  const current = () => sameRoom(generation, token)
  roomLoading.value = true
  roomError.value = ''
  try {
    await refreshAccess(active, current)
    if (current() && roomId.value && !invited.value) await loadLatestHistory(roomId.value, current)
  } catch (error) {
    if (current() && isAxiosError(error) && error.response?.status === 404) {
      markRoomUnavailable()
      return
    }
    if (sameRoom(generation, token)) roomError.value = errorText(error, '会话加载失败')
  } finally {
    if (sameRoom(generation, token)) roomLoading.value = false
  }
}

function nearBottom() {
  const el = scrollbox.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 72
}

async function jumpToLatest() {
  const generation = selection
  await nextTick()
  if (generation !== selection) return
  const el = scrollbox.value
  if (el) el.scrollTop = el.scrollHeight
  newMessages.value = false
  markRead()
}

function markRead() {
  if (!nearBottom() || document.hidden || invited.value || !sameAccount(auth.token)) return
  const id = roomId.value
  const seq = latestSequence()
  if (id && seq && seq > lastRead) {
    const old = lastRead
    const generation = selection
    const token = auth.token
    lastRead = seq
    void chat.read(id, seq).catch(() => {
      if (sameRoom(generation, token) && lastRead === seq) lastRead = old
    })
  }
}

function onScroll() {
  if (nearBottom()) newMessages.value = false
  markRead()
}

function latestSequence() {
  return messages.value[messages.value.length - 1]?.seq ?? 0
}

function merge(incoming: chat.Message[]) {
  if (!incoming.length) return
  const pinned = nearBottom()
  const bySequence = new Map(messages.value.map(message => [message.seq, message]))
  for (const message of incoming) bySequence.set(message.seq, message)
  messages.value = [...bySequence.values()].sort((a, b) => a.seq - b.seq)
  if (pinned) void jumpToLatest()
  else if (incoming.some(message => message.seq > lastRead)) newMessages.value = true
}

async function older() {
  const id = roomId.value
  const first = messages.value[0]
  if (!id || !first || loadingOlder.value || polling.value) return
  const generation = selection
  const token = auth.token
  loadingOlder.value = true
  try {
    const current = () => sameRoom(generation, token)
    const result = await chat.history(id, { beforeSeq: first.seq })
    if (!current()) return
    if (!sameHistoryVersion(result)) {
      clearHistory()
      await loadLatestHistory(id, current)
      return
    }
    const previous = scrollbox.value?.scrollHeight ?? 0
    messages.value = [...result.items, ...messages.value]
    hasOlder.value = result.hasMore
    await nextTick()
    if (sameRoom(generation, token) && scrollbox.value) scrollbox.value.scrollTop += scrollbox.value.scrollHeight - previous
  } catch (error) {
    if (!sameRoom(generation, token)) return
    if (isAxiosError(error) && error.response?.status === 404) markRoomUnavailable()
    else actionError.value = errorText(error, '历史消息加载失败')
  } finally {
    if (sameRoom(generation, token)) loadingOlder.value = false
  }
}

async function pollRoom() {
  const active = target.value
  const token = auth.token
  if (!active || !sameAccount(token) || roomLoading.value || busy.value || polling.value || loadingOlder.value) return
  polling.value = true
  const generation = selection
  const revision = mutation
  const current = () => sameRoom(generation, token) && !busy.value && mutation === revision
  try {
    await refreshAccess(active, current)
    if (!current()) return
    const id = roomId.value
    if (!id || invited.value) {
      roomError.value = ''
      return
    }
    if (!historyVersion) {
      await loadLatestHistory(id, current)
      return
    }
    while (current()) {
      const afterSeq = latestSequence()
      const result = await chat.history(id, { afterSeq })
      if (!current()) return
      if (!sameHistoryVersion(result)) {
        clearHistory()
        await loadLatestHistory(id, current)
        return
      }
      merge(result.items)
      if (!result.hasMore || latestSequence() <= afterSeq) break
    }
    markRead()
  } catch (error) {
    if (current() && isAxiosError(error) && error.response?.status === 404) markRoomUnavailable()
  } finally {
    polling.value = false
    if (realtimeDirty) scheduleRealtimeRefresh()
  }
}

async function act<T>(work: () => Promise<T>, success: (result: T) => void, fallback = '操作失败，请重试') {
  if (busy.value || !sameAccount(auth.token)) return
  const generation = selection
  const token = auth.token
  mutation++
  busy.value = true
  actionError.value = ''
  try {
    const result = await work()
    if (!sameRoom(generation, token)) return
    success(result)
    void loadList('invalidate')
    void loadRequestCount(true)
  } catch (error) {
    if (sameRoom(generation, token)) actionError.value = errorText(error, fallback)
  } finally {
    if (sameRoom(generation, token)) busy.value = false
  }
}

function send() {
  const active = target.value
  const content = draft.value.trim()
  if (!content || !active || busy.value) return
  if (!retry || retry.content !== content) retry = { clientMessageId: crypto.randomUUID(), content }
  const request = retry
  if ('peer' in active) {
    void act(() => chat.sendDirect(active.peer, request), result => {
      draft.value = ''
      retry = null
      const newRoom = direct.value?.id !== result.conversationId
      if (direct.value) direct.value = { ...direct.value, id: result.conversationId, state: result.state, quota: result.quota,
        canSend: result.state === 'ACTIVE', canDecide: false }
      if (newRoom) void loadRoom()
      else void pollAfterSend()
    }, '发送结果未确认，请重试')
  } else {
    void act(() => chat.sendGroup(active.group.id, request), () => {
      draft.value = ''
      retry = null
      void pollAfterSend()
    }, '发送结果未确认，请重试')
  }
}

async function pollAfterSend() {
  const generation = selection
  await nextTick()
  if (selection !== generation) return
  if (roomLoading.value || polling.value || loadingOlder.value || busy.value) {
    queueRealtimeRefresh()
    return
  }
  await pollRoom()
}

function decide(accept: boolean) {
  const id = roomId.value
  if (id) void act(() => chat.decide(id, accept), detail => { direct.value = detail })
}

function respond(accept: boolean) {
  const item = group.value
  if (item) void act(() => chat.respond(item.id, accept), () => {
    if (accept) { tab.value = 'messages'; openPreview({ ...item, membership: 'JOINED' }) }
    else select(null)
  })
}

function startGroup() {
  select(null)
  creating.value = true
  groupTitle.value = ''
  invitees.value = []
}

function create() {
  const title = groupTitle.value.trim()
  if (!title || !invitees.value.length) return
  void act(() => chat.createGroup(title, invitees.value), id => {
    tab.value = 'messages'
    openPreview({ id, title, peerId: null, ownerId: userId.value, state: 'ACTIVE', membership: 'JOINED', preview: null, unread: false })
  })
}

async function manage() {
  managerOpen.value = !managerOpen.value
  if (managerOpen.value) await refreshMembers()
}

async function refreshMembers() {
  const id = group.value?.id
  const generation = selection
  const token = auth.token
  if (!id) return
  try {
    const people = await chat.members(id)
    if (sameRoom(generation, token)) members.value = people
  } catch (error) {
    if (sameRoom(generation, token)) actionError.value = errorText(error, '加载群成员失败')
  }
}

function invite() {
  const id = group.value?.id
  const peer = selectedInvite.value[0]
  if (id && peer) void act(() => chat.invite(id, peer), () => { selectedInvite.value = []; void refreshMembers() })
}

function transfer(peer: chat.Person) {
  const item = group.value
  if (item && window.confirm(`将群主转让给 ${peer.name}？`)) {
    void act(() => chat.transfer(item.id, peer.id), () => { item.ownerId = peer.id; void refreshMembers() })
  }
}

function remove(peer: number) {
  const id = group.value?.id
  if (!id || !window.confirm(peer === userId.value ? '退出这个群聊？' : '移除此成员？')) return
  void act(() => chat.remove(id, peer), () => { if (peer === userId.value) select(null); else void refreshMembers() })
}

function closeGroup() {
  const id = group.value?.id
  if (id && window.confirm('解散后，成员将无法继续访问这个群聊。确认解散？')) {
    void act(() => chat.closeGroup(id), () => select(null))
  }
}

function setBlocked(peer: number, blocked: boolean) {
  if (blocked && !window.confirm('拉黑后，双方将无法互发私信或互相邀请入群。确认拉黑？')) return
  void act(async () => {
    await chat.block(peer, blocked)
    return direct.value?.peer.id === peer ? chat.direct(peer) : null
  }, result => { if (result) direct.value = result })
}

function changePreferences(event: Event) {
  const input = event.target as HTMLInputElement
  const requested = input.checked
  input.checked = allowRequests.value === true
  void act(() => chat.savePreferences(requested), result => { allowRequests.value = result })
}

function openReport(message: chat.Message) {
  reportId.value = message.id
  reportOpen.value = true
}

function canRecall(message: chat.Message): boolean {
  return message.sender.id === userId.value && message.content !== null && !message.recalled
    && now.value - Date.parse(message.createdAt) <= 120_000
}

function recallMessage(message: chat.Message) {
  const conversation = roomId.value
  if (!conversation || busy.value || !canRecall(message)) return
  void act(() => chat.recallMessage(conversation, message.id), () => {
    messages.value = messages.value.map(item => item.id === message.id
      ? { ...item, recalled: true, content: null }
      : item)
    void pollAfterSend()
    void loadList()
  }, '撤回失败，请重试')
}

function changeTab(value: Tab) {
  if (value === tab.value) return
  tab.value = value
  page.value = 1
  total.value = 0
  listError.value = ''
  previews.value = []
  friends.value = []
  if (value === 'settings') select(null)
}

function tick() {
  now.value = Date.now()
  if (document.hidden || !sameAccount(auth.token)) return
  listPoll++
  const reconcile = listPoll % 4 === 0
  if (realtimeReady && !reconcile) return
  void pollRoom()
  if (tab.value === 'messages' || tab.value === 'requests') void loadList('poll')
  void loadRequestCount()
}

function scheduleRealtimeRefresh() {
  if (document.hidden || realtimeTimer !== undefined || !sameAccount(auth.token)) return
  realtimeTimer = window.setTimeout(() => {
    realtimeTimer = undefined
    if (document.hidden || busy.value || roomLoading.value || polling.value || loadingOlder.value || !sameAccount(auth.token)) return
    realtimeDirty = false
    if (tab.value === 'messages' || tab.value === 'requests') void loadList('invalidate')
    void loadRequestCount(true)
    void pollRoom()
  }, 80)
}

function queueRealtimeRefresh() {
  realtimeDirty = true
  scheduleRealtimeRefresh()
}

function resetRealtime() {
  stopRealtime?.()
  stopRealtime = undefined
  realtimeDirty = false
  window.clearTimeout(realtimeTimer)
  realtimeTimer = undefined
  const token = auth.token
  if (sameAccount(token)) {
    stopRealtime = connectChatRealtime(token, () => sameAccount(token), ready => { realtimeReady = ready }, queueRealtimeRefresh)
  }
}

function onVisible() {
  if (document.hidden) return
  if (tab.value === 'friends' || tab.value === 'settings') void loadList('invalidate')
  queueRealtimeRefresh()
}

function fromRoute() {
  const peer = Number(route.query.peer)
  if (Number.isSafeInteger(peer) && peer > 0) select({ peer })
}

watch([tab, page], ([nextTab], [previousTab]) => {
  void loadList()
  if (previousTab === 'requests' && nextTab !== 'requests') void loadRequestCount()
})
watch([busy, roomLoading, loadingOlder], () => { if (realtimeDirty) scheduleRealtimeRefresh() })
watch(() => route.query.peer, fromRoute)
watch(() => auth.token, () => {
  listRequest = undefined
  requestCountRequest = undefined
  select(null)
  previews.value = []
  friends.value = []
  blocked.value = []
  allowRequests.value = null
  requestCount.value = 0
  total.value = 0
  listLoading.value = false
  listError.value = ''
  resetRealtime()
  if (sameAccount(auth.token)) { fromRoute(); void loadList(); void loadRequestCount() }
}, { flush: 'sync' })

onMounted(() => {
  mounted = true
  fromRoute()
  void loadList()
  void loadRequestCount()
  resetRealtime()
  timer = window.setInterval(tick, 3000)
  document.addEventListener('visibilitychange', onVisible)
})

onUnmounted(() => {
  mounted = false
  resetRealtime()
  window.clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<template>
  <section class="ui-page ui-panel messages" :class="{ 'messages--open': target || creating }">
    <aside class="messages__list" aria-label="消息导航">
      <div class="messages__top">
        <h1 class="ui-title">消息</h1>
        <button type="button" class="ui-button" @click="startGroup">发起群聊</button>
      </div>
      <nav class="messages__tabs" aria-label="消息分类">
        <button v-for="item in tabs" :key="item.id" class="messages__tab" type="button"
          :aria-pressed="tab === item.id" @click="changeTab(item.id)"
        >{{ item.label }}<span v-if="item.id === 'requests' && requestCount" class="messages__count">{{ requestCount }}</span></button>
      </nav>
      <div class="messages__entries ui-scroll-on-interaction">
        <div v-if="listError" class="ui-error my-2" role="alert">{{ listError }} <button class="ui-button" type="button" @click="loadList()">重试</button></div>
        <p v-else-if="listLoading && !total && allowRequests === null" class="ui-muted p-3 text-sm" role="status">正在加载…</p>
        <template v-else-if="tab === 'settings'">
          <label class="flex items-start gap-2 p-3 text-sm">
            <input v-if="allowRequests !== null" type="checkbox" :checked="allowRequests" :disabled="busy"
              @change="changePreferences" />
            允许非好友发起消息请求
          </label>
          <h2 class="p-3 text-sm font-semibold">拉黑名单</h2>
          <p v-if="!blocked.length" class="ui-muted px-3 text-sm">名单为空</p>
          <div v-for="peer in blocked" :key="peer.id" class="flex items-center justify-between gap-2 p-3 text-sm">
            <span>{{ peer.name }}</span>
            <button class="ui-button" type="button" :disabled="busy" @click="setBlocked(peer.id, false)">解除拉黑</button>
          </div>
          <p v-if="actionError" class="ui-error" role="alert">{{ actionError }}</p>
        </template>
        <template v-else-if="tab === 'friends'">
          <div v-if="!friends.length" class="space-y-2 p-3 text-sm">
            <p class="ui-muted">互相关注或接受好友申请后，好友会出现在这里。</p>
            <RouterLink :to="{ name: 'users' }" class="ui-button">查找用户</RouterLink>
          </div>
          <button v-for="user in friends" :key="user.id" class="messages__entry"
            type="button" @click="select({ peer: Number(user.id) })"
          ><span class="font-semibold">{{ user.nickname || user.username }}</span><span class="ui-muted text-xs">发私信</span></button>
        </template>
        <template v-else-if="tab === 'friendRequests'">
          <p v-if="!friends.length" class="ui-muted p-3 text-sm">暂无收到的好友申请</p>
          <div v-for="user in friends" :key="user.id" class="space-y-2 p-3">
            <RouterLink :to="'/users/' + user.id" class="font-medium">{{ user.nickname || user.username }}</RouterLink>
            <FriendRequestActions :peer="user.id" incoming @decided="loadList()" />
          </div>
        </template>
        <template v-else>
          <p v-if="!previews.length" class="ui-muted p-3 text-sm">{{ tab === 'requests' ? '暂无消息请求或群邀请' : '还没有会话' }}</p>
          <button v-for="item in previews" :key="item.id" type="button" class="messages__entry"
            :aria-pressed="roomId === item.id" @click="openPreview(item)"
          >
            <span class="flex items-center justify-between gap-2"><strong class="truncate">{{ item.title }}</strong>
              <span v-if="item.unread" class="messages__dot" aria-label="有未读消息"></span></span>
            <span class="ui-muted truncate text-xs">{{ item.membership === 'INVITED' ? '群聊邀请' :
              item.state === 'PENDING' ? (tab === 'requests' ? '消息请求' : '等待对方接受') :
              item.preview || (item.peerId ? '私信' : '群聊') }}</span>
          </button>
        </template>
        <div v-if="tab !== 'settings' && total > 30" class="flex flex-wrap items-center justify-between gap-2 p-2 text-sm">
          <button type="button" class="ui-button" :disabled="page === 1 || listLoading" @click="page--">上一页</button>
          <span>{{ page }} / {{ Math.ceil(total / 30) }}</span>
          <button type="button" class="ui-button" :disabled="page * 30 >= total || listLoading" @click="page++">下一页</button>
        </div>
      </div>
    </aside>

    <section v-if="creating" class="messages__main messages__content">
      <div class="flex items-center gap-2">
        <button type="button" class="ui-button" @click="back">返回</button>
        <h2 class="font-semibold">发起群聊</h2>
      </div>
      <form class="space-y-4" @submit.prevent="create">
        <label class="block space-y-1 text-sm"><span>群名称</span>
          <input v-model="groupTitle" class="ui-field w-full" maxlength="80" required /></label>
        <ChatFriendPicker v-model="invitees" :limit="49" />
        <p v-if="actionError" role="alert" class="ui-error">{{ actionError }}</p>
        <button type="submit" class="ui-button-primary" :disabled="busy || !groupTitle.trim() || !invitees.length">创建群聊</button>
      </form>
    </section>

    <section v-else-if="target" class="messages__main">
      <header class="messages__header">
        <button type="button" class="ui-button messages__back" @click="back">返回</button>
        <h2 class="min-w-0 flex-1 truncate font-semibold" :title="title">{{ title }}</h2>
        <button v-if="group && !invited && !roomError" type="button" class="ui-button" :aria-expanded="managerOpen" @click="manage">成员</button>
        <button v-if="direct" type="button" class="ui-button" :disabled="busy" @click="setBlocked(direct.peer.id, !direct.blockedByMe)">
          {{ direct.blockedByMe ? '解除拉黑' : '拉黑' }}
        </button>
      </header>

      <div v-if="managerOpen && group" class="messages__management ui-scroll-on-interaction space-y-3">
        <div v-for="member in members" :key="member.user.id" class="flex flex-wrap items-center gap-2 text-sm">
          <span class="min-w-0 flex-1">{{ member.user.name }}<span v-if="member.user.id === group.ownerId" class="ui-muted ml-2">群主</span>
            <span v-else-if="member.state === 'INVITED'" class="ui-muted ml-2">待加入</span></span>
          <template v-if="owner && member.user.id !== userId">
            <button v-if="member.state === 'JOINED'" type="button" class="ui-button" :disabled="busy" @click="transfer(member.user)">转让群主</button>
            <button type="button" class="ui-button" :disabled="busy" @click="remove(member.user.id)">{{ member.state === 'INVITED' ? '取消邀请' : '移除' }}</button>
          </template>
        </div>
        <form v-if="owner" class="space-y-2" @submit.prevent="invite">
          <ChatFriendPicker v-model="selectedInvite" :exclude="members.map(member => member.user.id)" :limit="1" />
          <button type="submit" class="ui-button" :disabled="busy || !selectedInvite.length">邀请入群</button>
        </form>
        <div class="flex flex-wrap gap-2">
          <button v-if="Number.isSafeInteger(userId)" type="button" class="ui-button" :disabled="busy" @click="remove(userId)">退出群聊</button>
          <button v-if="owner" type="button" class="ui-button" :disabled="busy" @click="closeGroup">解散群聊</button>
        </div>
      </div>

      <div v-if="roomError" class="messages__content" role="alert"><p class="ui-error">{{ roomError }}</p>
        <button type="button" class="ui-button" @click="loadRoom">重试</button></div>
      <div v-else-if="invited" class="messages__content">
        <p class="text-sm">你收到了加入「{{ title }}」的邀请。</p>
        <div class="flex flex-wrap gap-2">
          <button type="button" class="ui-button-primary" :disabled="busy" @click="respond(true)">加入群聊</button>
          <button type="button" class="ui-button" :disabled="busy" @click="respond(false)">拒绝</button>
        </div>
      </div>
      <div v-else ref="scrollbox" class="messages__history ui-scroll-on-interaction" @scroll.passive="onScroll">
        <button v-if="hasOlder" type="button" class="ui-button mx-auto" :disabled="loadingOlder || polling" @click="older">加载更早的消息</button>
        <p v-if="roomLoading" class="ui-muted text-center text-sm" role="status">正在加载消息…</p>
        <p v-else-if="!messages.length" class="ui-muted text-center text-sm">暂无消息</p>
        <article v-for="message in messages" :key="message.id" class="messages__bubble"
          :class="{ 'messages__bubble--mine': message.sender.id === userId }">
          <div class="ui-muted flex flex-wrap items-center gap-2 text-xs">
            <span>{{ message.sender.name }}</span>
            <time :datetime="message.createdAt">{{ new Date(message.createdAt).toLocaleString('zh-CN') }}</time>
            <button v-if="message.sender.id !== userId && message.content !== null" class="underline" type="button"
              :aria-label="'举报来自 ' + message.sender.name + ' 的消息'" @click="openReport(message)">举报</button>
            <button v-if="canRecall(message)" type="button" class="underline" :disabled="busy"
              @click="recallMessage(message)">撤回</button>
          </div>
          <p class="messages__text">{{ message.recalled ? '消息已撤回' : (message.content ?? '消息已被隐藏') }}</p>
        </article>
      </div>
      <div v-if="actionError" class="ui-error mx-4 mb-2" role="alert">{{ actionError }}</div>
      <div v-if="!roomError && !invited && !roomLoading" class="messages__composer">
        <button v-if="newMessages" type="button" class="ui-button" @click="jumpToLatest">有新消息 · 查看</button>
        <div v-if="direct?.canDecide" class="flex flex-wrap items-center gap-2">
          <button type="button" class="ui-button-primary" :disabled="busy" @click="decide(true)">接受请求</button>
          <button type="button" class="ui-button" :disabled="busy" @click="decide(false)">拒绝</button>
        </div>
        <template v-if="group || direct?.canSend">
          <p v-if="direct && !(direct.friend ?? direct.mutual)" class="ui-muted text-xs">
            {{ direct.state === 'NEW' ? '首次私信将作为消息请求发送。' : '' }}
            今日非好友私信剩余 {{ direct.quota.remaining }} / {{ direct.quota.limit }} 条
            <span v-if="!direct.quota.remaining"> · {{ resetTime(direct.quota) }}恢复</span>
          </p>
          <form class="flex items-end gap-2" @submit.prevent="send">
            <textarea v-model="draft" class="ui-field min-w-0 flex-1 resize-y" maxlength="1000" rows="2"
              aria-label="消息内容" placeholder="输入消息" :disabled="busy" />
            <button type="submit" class="ui-button-primary shrink-0"
              :disabled="busy || !draft.trim() || Boolean(direct && !(direct.friend ?? direct.mutual) && !direct.quota.remaining && !retry)"
            >{{ busy ? '发送中' : '发送' }}</button>
          </form>
        </template>
        <p v-else-if="direct && !direct.canDecide" class="ui-muted text-sm">
          {{ direct.state === 'PENDING' && !direct.blockedByMe ? '等待对方接受消息请求' : '当前无法发送消息' }}
        </p>
      </div>
    </section>
    <div v-else class="messages__empty ui-muted">选择一个会话，或从好友列表开始私信</div>

    <ReportDialog v-if="reportOpen" v-model="reportOpen" target-type="CHAT_MESSAGE" :target-id="reportId" :is-logged-in="auth.isLoggedIn" />
  </section>
</template>

<style scoped>
.messages {
  display: grid;
  grid-template-columns: minmax(13rem, 0.42fr) minmax(0, 1fr);
  min-width: 0;
  min-height: 28rem;
  height: clamp(28rem, calc(100dvh - 9rem), 56rem);
  overflow: hidden;
}
.messages__list, .messages__main { display: flex; flex-direction: column; min-height: 0; min-width: 0; }
.messages__list { border-inline-end: 1px solid var(--ui-border); }
.messages__top, .messages__header {
  display: flex; align-items: center; justify-content: space-between; gap: 0.5rem;
  padding: 0.85rem 1rem; border-block-end: 1px solid var(--ui-border);
}
.messages__top .ui-title { font-size: 1.125rem; white-space: nowrap; }
.messages__tabs { display: flex; flex-wrap: wrap; gap: 0.25rem; padding: 0.5rem; }
.messages__tab { padding: 0.5rem 0.55rem; border-radius: 0.5rem; font-size: 0.875rem; color: var(--ui-muted); }
.messages__tab[aria-pressed="true"], .messages__entry[aria-pressed="true"] { background: var(--ui-subtle); color: var(--ui-accent); }
.messages__entries { flex: 1; overflow: auto; padding: 0.25rem 0.5rem 0.75rem; }
.messages__entry { width: 100%; display: grid; gap: 0.2rem; padding: 0.65rem 0.75rem; text-align: start; border-radius: 0.6rem; }
.messages__entry:hover, .messages__tab:hover { background: var(--ui-subtle); }
.messages__dot { width: 0.48rem; height: 0.48rem; border-radius: 100%; flex: none; background: var(--ui-accent); }
.messages__count { color: var(--ui-accent); margin-inline-start: 0.3rem; font-size: 0.75rem; }
.messages__content { padding: 1rem; display: grid; gap: 0.75rem; align-content: start; overflow: auto; }
.messages__empty { align-self: center; text-align: center; padding: 1rem; font-size: 0.875rem; }
.messages__history { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 0.9rem; padding: 1rem; }
.messages__management { overflow: auto; max-height: 48%; padding: 0.75rem 1rem; border-block-end: 1px solid var(--ui-border); }
.messages__bubble { align-self: flex-start; max-width: 86%; }
.messages__bubble--mine { align-self: flex-end; }
.messages__text {
  border: 1px solid var(--ui-border); border-radius: 0.75rem; padding: 0.55rem 0.75rem; margin-top: 0.25rem;
  white-space: pre-wrap; overflow-wrap: anywhere; font-size: 0.9rem;
}
.messages__bubble--mine .messages__text { background: var(--ui-subtle); }
.messages__composer { display: grid; gap: 0.45rem; padding: 0.75rem 1rem; border-block-start: 1px solid var(--ui-border); }
.messages__back { display: none; }
@media (max-width: 767px) {
  .messages { grid-template-columns: minmax(0, 1fr); height: clamp(25rem, calc(100dvh - 9rem), 56rem); min-height: 25rem; }
  .messages--open .messages__list, .messages__empty { display: none; }
  .messages__list { border: 0; }
  .messages__back { display: inline-flex; }
}
</style>
