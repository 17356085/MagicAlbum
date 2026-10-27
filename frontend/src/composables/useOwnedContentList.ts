import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { Id, PageResult } from '@/types'

export type OwnedContentSort = 'createdAt' | 'updatedAt'

export interface OwnedContentQuery {
  q: string
  sectionId: string
  page: number
  size: number
  sort: OwnedContentSort
}

export interface OwnedContentFetchQuery extends Omit<OwnedContentQuery, 'sectionId'> {
  sectionId?: string
}

export interface OwnedContentMessages {
  loadError: string
  loginRequired: string
  sessionChanged: string
  deleteError: string
  confirmTitle: string
  confirmMessage: string
}

export interface UseOwnedContentListOptions<T extends { id: Id }> {
  initialSort: OwnedContentSort
  fetchPage: (query: OwnedContentFetchQuery) => Promise<PageResult<T>>
  deleteItem: (id: Id) => Promise<unknown>
  messages: OwnedContentMessages
}

interface SessionContext {
  token: string
  epoch: number
  ownerId: Id | null
}

function createInitialQuery(initialSort: OwnedContentSort): OwnedContentQuery {
  return { q: '', sectionId: '', page: 1, size: 10, sort: initialSort }
}

function createEmptyList<T extends { id: Id }>(): PageResult<T> {
  return { items: [], page: 1, size: 10, total: 0 }
}

function normalizeId(value: unknown): Id | null {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? value : null
  if (typeof value === 'string' && value.trim()) return value.trim()
  return null
}

function idKey(value: unknown): string | null {
  const normalized = normalizeId(value)
  return normalized === null ? null : String(normalized)
}

function sameId(left: unknown, right: unknown): boolean {
  const leftKey = idKey(left)
  const rightKey = idKey(right)
  return leftKey !== null && rightKey !== null && leftKey === rightKey
}

export function useOwnedContentList<T extends { id: Id }>(options: UseOwnedContentListOptions<T>) {
  const authStore = useAuthStore()
  const { token, user } = storeToRefs(authStore)
  const query = ref<OwnedContentQuery>(createInitialQuery(options.initialSort))
  const loading = ref(false)
  const error = ref('')
  const deleteError = ref('')
  const list = shallowRef<PageResult<T>>(createEmptyList<T>())
  const inputPage = ref('1')

  let loadRequestSeq = 0
  let deleteRequestSeq = 0
  let sessionEpoch = 0
  let reloadQueued = false
  let mounted = false
  let disposed = false
  let observedToken = ''
  let observedStoredToken = ''
  let observedOwnerKey: string | null = null

  const showDeleteConfirm = ref(false)
  const deleting = ref(false)
  const pendingDeleteId = ref<Id | null>(null)
  const pendingDeleteSession = ref<SessionContext | null>(null)
  const pendingDeleteQueryKey = ref('')
  const deleteDialogTitle = computed(() => options.messages.confirmTitle)
  const deleteDialogMessage = computed(() => deleteError.value || options.messages.confirmMessage)
  const deleteConfirmText = computed(() => deleteError.value ? '重试' : '确认')

  function currentToken(): string {
    return String(token.value || '').trim()
  }

  function storedToken(): string {
    return String(getStoredAccessToken() || '').trim()
  }

  function currentOwnerId(): Id | null {
    return normalizeId(user.value?.id ?? user.value?.userId)
  }

  function captureSession(): SessionContext | null {
    const accessToken = currentToken()
    if (!hasRealToken(accessToken) || accessToken !== storedToken()) return null
    return { token: accessToken, epoch: sessionEpoch, ownerId: currentOwnerId() }
  }

  function sameOwner(expected: Id | null, actual: Id | null): boolean {
    // A token-only session may be hydrated with its confirmed owner later.
    if (expected === null) return true
    return actual !== null && idKey(expected) === idKey(actual)
  }

  function isCurrentSession(context: SessionContext): boolean {
    return !disposed
      && context.epoch === sessionEpoch
      && currentToken() === context.token
      && storedToken() === context.token
      && sameOwner(context.ownerId, currentOwnerId())
  }

  function isCurrentOperation(context: SessionContext, sequence: number, currentSequence: number): boolean {
    return isCurrentSession(context) && sequence === currentSequence
  }

  function isDisplayedItem(id: Id): boolean {
    return (list.value.items || []).some((item) => sameId(item.id, id))
  }

  function clearDeleteState(): void {
    showDeleteConfirm.value = false
    pendingDeleteId.value = null
    pendingDeleteSession.value = null
    pendingDeleteQueryKey.value = ''
    deleteError.value = ''
  }

  function resetPrivateState(): void {
    query.value = createInitialQuery(options.initialSort)
    inputPage.value = '1'
    error.value = ''
    clearDeleteState()
    deleting.value = false
    list.value = createEmptyList<T>()
  }

  function queueReload(): void {
    if (disposed || !mounted || reloadQueued) return
    reloadQueued = true
    queueMicrotask(() => {
      reloadQueued = false
      if (disposed || !mounted) return
      void load()
    })
  }

  function resetAndQueueReload(): void {
    if (disposed) return
    sessionEpoch += 1
    loadRequestSeq += 1
    deleteRequestSeq += 1
    resetPrivateState()
    loading.value = false
    queueReload()
  }

  function sessionUnavailableMessage(): string {
    return hasRealToken(currentToken()) || hasRealToken(storedToken())
      ? options.messages.sessionChanged
      : options.messages.loginRequired
  }

  function clearUnavailableSession(): void {
    if (disposed) return
    sessionEpoch += 1
    loadRequestSeq += 1
    deleteRequestSeq += 1
    resetPrivateState()
    loading.value = false
    if (hasRealToken(currentToken()) && currentToken() === storedToken()) {
      queueReload()
    } else {
      error.value = sessionUnavailableMessage()
    }
  }

  function observeSessionSnapshot(): boolean {
    const nextToken = currentToken()
    const nextStoredToken = storedToken()
    const nextOwnerKey = idKey(currentOwnerId())
    const tokenChanged = nextToken !== observedToken || nextStoredToken !== observedStoredToken
    const sameTokenHydration = !tokenChanged && observedOwnerKey === null && nextOwnerKey !== null
    const ownerChanged = !sameTokenHydration && nextOwnerKey !== observedOwnerKey
    observedToken = nextToken
    observedStoredToken = nextStoredToken
    observedOwnerKey = nextOwnerKey
    return tokenChanged || ownerChanged
  }

  async function load(expectedContext?: SessionContext): Promise<void> {
    if (disposed) return
    const context = expectedContext || captureSession()
    if (!context) {
      clearUnavailableSession()
      return
    }
    if (!isCurrentSession(context)) {
      if (context.epoch === sessionEpoch) clearUnavailableSession()
      return
    }

    const requestSeq = ++loadRequestSeq
    const requestQuery: OwnedContentFetchQuery = {
      q: query.value.q,
      sectionId: query.value.sectionId || undefined,
      page: query.value.page,
      size: query.value.size,
      sort: query.value.sort,
    }
    loading.value = true
    error.value = ''
    try {
      const data = await options.fetchPage(requestQuery)
      if (!isCurrentOperation(context, requestSeq, loadRequestSeq)) return
      list.value = data
    } catch (_) {
      if (isCurrentOperation(context, requestSeq, loadRequestSeq)) error.value = options.messages.loadError
    } finally {
      if (isCurrentOperation(context, requestSeq, loadRequestSeq)) {
        loading.value = false
      } else if (!disposed && requestSeq === loadRequestSeq && context.epoch === sessionEpoch) {
        // A storage change can race a response before the Pinia/storage event
        // bridge runs. Clear stale private state without touching a newer load.
        clearUnavailableSession()
      }
    }
  }

  function retryLoad(): void {
    void load()
  }

  function queryKey(value: OwnedContentQuery): string {
    return JSON.stringify([value.q, value.sectionId, value.page, value.size, value.sort])
  }

  function askRemove(id: Id): void {
    if (deleting.value) return
    const context = captureSession()
    if (!context || !isCurrentSession(context)) {
      clearUnavailableSession()
      return
    }
    if (!isDisplayedItem(id)) return
    pendingDeleteId.value = id
    pendingDeleteSession.value = context
    pendingDeleteQueryKey.value = queryKey(query.value)
    deleteError.value = ''
    showDeleteConfirm.value = true
  }

  function removeLocally(id: Id, deletedQueryKey: string): void {
    const currentItems = list.value.items || []
    const nextItems = currentItems.filter((item) => !sameId(item.id, id))
    if (nextItems.length === currentItems.length) return

    const currentTotal = Math.max(0, Number(list.value.total || currentItems.length))
    const nextTotal = Math.max(0, currentTotal - 1)
    const pageSize = Number(list.value.size || query.value.size || 10) || 10
    const nextTotalPages = Math.max(1, Math.ceil(nextTotal / pageSize))
    // Totals belong to the displayed query. They cannot constrain a new query
    // selected while the deletion was pending.
    const sameQuery = queryKey(query.value) === deletedQueryKey
    const nextPage = sameQuery
      ? Math.min(Math.max(1, query.value.page || 1), nextTotalPages)
      : list.value.page
    if (sameQuery) query.value.page = nextPage
    list.value = { ...list.value, items: nextItems, page: nextPage, total: nextTotal }
  }

  async function confirmDelete(): Promise<void> {
    if (deleting.value) return
    const id = pendingDeleteId.value
    const context = pendingDeleteSession.value
    const deletedQueryKey = pendingDeleteQueryKey.value
    if (id == null || context == null) {
      clearDeleteState()
      return
    }
    if (!isCurrentSession(context)) {
      if (context.epoch === sessionEpoch) clearUnavailableSession()
      else clearDeleteState()
      return
    }
    if (!isDisplayedItem(id)) {
      clearDeleteState()
      return
    }

    const requestSeq = ++deleteRequestSeq
    const deleteId = id
    deleting.value = true
    deleteError.value = ''
    let deleted = false
    try {
      await options.deleteItem(deleteId)
      if (!isCurrentOperation(context, requestSeq, deleteRequestSeq)) return
      // Any list read that started before the delete can otherwise put the row
      // back after the server has accepted the deletion.
      loadRequestSeq += 1
      removeLocally(deleteId, deletedQueryKey)
      clearDeleteState()
      deleted = true
    } catch (_) {
      if (isCurrentOperation(context, requestSeq, deleteRequestSeq)) {
        deleteError.value = options.messages.deleteError
      }
    } finally {
      if (isCurrentOperation(context, requestSeq, deleteRequestSeq)) {
        deleting.value = false
      } else if (!disposed && requestSeq === deleteRequestSeq && context.epoch === sessionEpoch) {
        // Storage may drift before the auth bridge reports it. Do not leave the
        // old delete busy state or its private rows visible in that case.
        clearUnavailableSession()
      }
    }

    // The delete has already succeeded. A refresh failure is a list error and
    // must not turn this operation back into a retryable delete.
    if (deleted && isCurrentSession(context)) await load(context)
  }

  function cancelDelete(): void {
    if (deleting.value) return
    clearDeleteState()
  }

  const stopSessionWatch = watch(
    [() => currentToken(), () => storedToken(), () => idKey(currentOwnerId())],
    () => {
      if (observeSessionSnapshot()) resetAndQueueReload()
    },
    { flush: 'sync' },
  )

  function onAuthStateChanged(): void {
    if (disposed) return
    // Every explicit auth event starts a new epoch, including same-token events.
    observeSessionSnapshot()
    resetAndQueueReload()
  }

  function onStorageChanged(event: StorageEvent): void {
    if (disposed || (event.key !== null && event.key !== 'accessToken')) return
    if (observeSessionSnapshot()) resetAndQueueReload()
  }

  onMounted(() => {
    mounted = true
    observedToken = currentToken()
    observedStoredToken = storedToken()
    observedOwnerKey = idKey(currentOwnerId())
    window.addEventListener('auth-state-changed', onAuthStateChanged)
    window.addEventListener('storage', onStorageChanged)
    void load()
  })

  onUnmounted(() => {
    disposed = true
    mounted = false
    sessionEpoch += 1
    loadRequestSeq += 1
    deleteRequestSeq += 1
    reloadQueued = false
    stopSessionWatch()
    window.removeEventListener('auth-state-changed', onAuthStateChanged)
    window.removeEventListener('storage', onStorageChanged)
  })

  const totalPages = computed<number>(() => {
    const size = Number(list.value.size || query.value.size || 10)
    const total = Number(list.value.total || 0)
    const pages = Math.ceil(total / (size || 10))
    return Math.max(1, pages || 1)
  })

  function setPage(page: number): void {
    const target = Math.min(Math.max(1, page), totalPages.value)
    if (target === (query.value.page || 1)) return
    query.value.page = target
    void load()
  }

  function prevPage(): void {
    if (loading.value) return
    setPage((query.value.page || 1) - 1)
  }

  function nextPage(): void {
    if (loading.value) return
    setPage((query.value.page || 1) + 1)
  }

  function goToInputPage(): void {
    const raw = String(inputPage.value || '').trim()
    if (!raw || !/^\d+$/.test(raw)) {
      inputPage.value = String(query.value.page || 1)
      return
    }
    setPage(Math.min(Math.max(1, Number(raw)), totalPages.value))
  }

  watch(() => query.value.page, (value) => {
    inputPage.value = String(value || 1)
  }, { immediate: true })

  return {
    query,
    loading,
    error,
    deleteError,
    list,
    inputPage,
    showDeleteConfirm,
    deleting,
    totalPages,
    deleteDialogTitle,
    deleteDialogMessage,
    deleteConfirmText,
    load,
    retryLoad,
    askRemove,
    confirmDelete,
    cancelDelete,
    setPage,
    prevPage,
    nextPage,
    goToInputPage,
  }
}
