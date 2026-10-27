// 最近浏览：基于路由 afterEach 记录本地 localStorage，并提供读取 API
// 存储结构：[{ path, name, title, id, sectionId, sectionName, ts }]
// 保留最近 10 条，时间窗口默认 90 天；去重按 path
// 按账号隔离：本地存储键包含已验证用户ID；未登录使用 guest

import type { Id, RecentVisit } from '@/types'
import type { Router, RouteRecordNameGeneric } from 'vue-router'
import { getStoredAccessToken, getStoredCurrentUser, hasRealToken } from '@/utils/authStorage'

const STORAGE_KEY_BASE = 'recent_visits_v1'

export interface RecentVisitsIdentity {
  readonly token: string
  readonly ownerId: Id | null
}

export interface RecentVisitsScope {
  readonly token: string
  readonly ownerId: string | null
  readonly key: string
}

interface NormalizedRecentVisitsIdentity {
  token: string
  ownerId: string | null
}

function normalizeIdentity(identity?: RecentVisitsIdentity | null): NormalizedRecentVisitsIdentity {
  const token = hasRealToken(identity?.token) ? String(identity?.token) : ''
  if (!token) return { token: '', ownerId: null }
  const ownerId = identity?.ownerId
  return {
    token,
    ownerId: ownerId == null || !String(ownerId).trim() ? null : String(ownerId),
  }
}

function captureStoredIdentity(): NormalizedRecentVisitsIdentity | null {
  try {
    const storedToken = getStoredAccessToken()
    if (!hasRealToken(storedToken)) return { token: '', ownerId: null }
    const token = String(storedToken)
    const user = getStoredCurrentUser(token)
    const uid = user?.id ?? user?.userId
    if (uid == null || !String(uid).trim()) return null
    return { token, ownerId: String(uid) }
  } catch (_) {}
  return null
}

export function captureRecentVisitsScope(expectedIdentity?: RecentVisitsIdentity | null): RecentVisitsScope | null {
  const current = captureStoredIdentity()
  if (!current) return null

  if (expectedIdentity !== undefined) {
    const expected = normalizeIdentity(expectedIdentity)
    if (expected.token !== current.token) return null
    if (current.token && (expected.ownerId === null || expected.ownerId !== current.ownerId)) return null
  }

  return Object.freeze({
    token: current.token,
    ownerId: current.ownerId,
    key: `${STORAGE_KEY_BASE}:${current.ownerId ?? 'guest'}`,
  })
}

export function isRecentVisitsScopeCurrent(
  scope: RecentVisitsScope | null | undefined,
  expectedIdentity?: RecentVisitsIdentity | null,
): boolean {
  if (!scope) return false
  const current = captureRecentVisitsScope(expectedIdentity)
  return !!current
    && current.token === scope.token
    && current.ownerId === scope.ownerId
    && current.key === scope.key
}

function storageKey(scope?: RecentVisitsScope | null): string | null {
  if (scope === null) return null
  if (scope === undefined) return captureRecentVisitsScope()?.key ?? null
  return isRecentVisitsScopeCurrent(scope) ? scope.key : null
}
const MAX_ITEMS = 10
const MAX_AGE_MS = 90 * 24 * 60 * 60 * 1000 // 90 天
// 仅记录“文章/帖子详情”路由
const ALLOWED_ROUTE_NAMES = new Set(['thread-detail'])

function normalizePath(path: string | null | undefined): string {
  const p = String(path || '')
  // 去除 query 与 hash，保持基础路径用于去重与展示
  return p.replace(/[?#].*$/, '')
}

function decodeStoredVisit(value: unknown): RecentVisit | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const item = value as Record<string, unknown>
  if (typeof item.path !== 'string' || !item.path.trim()) return null
  const ts = typeof item.ts === 'number'
    ? item.ts
    : typeof item.ts === 'string' && item.ts.trim() ? Number(item.ts) : Number.NaN
  if (!Number.isFinite(ts) || !Number.isFinite(new Date(ts).getTime())) return null

  const visit: RecentVisit = { path: item.path, ts }
  for (const field of ['name', 'title', 'sectionName'] as const) {
    const text = item[field]
    if (typeof text === 'string') visit[field] = text
  }
  for (const field of ['id', 'sectionId'] as const) {
    const id = item[field]
    if ((typeof id === 'string' && id.trim()) || (typeof id === 'number' && Number.isFinite(id))) visit[field] = id
  }
  return visit
}

function readStoredVisits(key: string | null = storageKey()): RecentVisit[] {
  if (!key) return []
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return []
    const arr = JSON.parse(raw) as unknown
    if (!Array.isArray(arr)) return []
    const visits: RecentVisit[] = []
    for (const item of arr) {
      const visit = decodeStoredVisit(item)
      if (visit) visits.push(visit)
    }
    return visits
  } catch (_) {
    return []
  }
}

function writeRaw(list: RecentVisit[], key: string | null = storageKey()): void {
  if (!key) return
  try { localStorage.setItem(key, JSON.stringify(list)) } catch (_) {}
  notifyUpdated()
}

function notifyUpdated(): void {
  // 触发事件，便于侧栏刷新
  try { window.dispatchEvent(new CustomEvent('recent-visits-updated')) } catch (_) {}
}

export function addVisit({
  path,
  name,
  title,
  id,
  sectionId,
  sectionName,
}: {
  path: string
  name?: string
  title?: string
  id?: Id | null
  sectionId?: Id
  sectionName?: string
}): void {
  const key = storageKey()
  if (!key) return
  const ts = Date.now()
  const list = readStoredVisits(key)
  const basePath = normalizePath(path)
  // 去重：优先按帖子 ID；无 ID 时按基础 path
  const filtered = list.filter(it => {
    const sameId = (id != null) && (String(it.id || '') === String(id))
    const samePath = String(normalizePath(it.path || '')) === String(basePath)
    return !(sameId || (!id && samePath))
  })
  filtered.unshift({ path: basePath, name, title, id: id ?? undefined, sectionId, sectionName, ts })
  // 过滤过期项与限制长度
  const recent = filtered.filter(it => (ts - Number(it.ts || 0)) <= MAX_AGE_MS).slice(0, MAX_ITEMS)
  writeRaw(recent, key)
}

export function getRecentVisits(limit = 5, scope?: RecentVisitsScope | null): RecentVisit[] {
  const key = storageKey(scope)
  if (!key) return []
  const now = Date.now()
  const list = readStoredVisits(key)
  const recent = list.filter(it => (now - Number(it.ts || 0)) <= MAX_AGE_MS)
  return recent.slice(0, limit)
}

export function getAllRecentVisits(scope?: RecentVisitsScope | null): RecentVisit[] {
  const key = storageKey(scope)
  if (!key) return []
  const now = Date.now()
  const list = readStoredVisits(key)
  return list.filter(it => (now - Number(it.ts || 0)) <= MAX_AGE_MS)
}

export function clearAllRecentVisits(scope?: RecentVisitsScope | null): void {
  const key = storageKey(scope)
  if (!key) return
  try { localStorage.removeItem(key) } catch (_) {}
  notifyUpdated()
}

export function removeRecentVisit(target: RecentVisit, scope?: RecentVisitsScope | null): void {
  const key = storageKey(scope)
  if (!key) return
  const targetPath = normalizePath(target.path)
  const targetId = target.id == null ? '' : String(target.id)
  const list = readStoredVisits(key).filter((it) => {
    const sameId = targetId && String(it.id || '') === targetId
    const samePath = normalizePath(it.path) === targetPath
    return !(sameId || samePath)
  })
  writeRaw(list, key)
}

export function pruneExpired(scope?: RecentVisitsScope | null): void {
  const key = storageKey(scope)
  if (!key) return
  const now = Date.now()
  const list = readStoredVisits(key)
  const pruned = list.filter(it => (now - Number(it.ts || 0)) <= MAX_AGE_MS)
  writeRaw(pruned.slice(0, MAX_ITEMS), key)
}

export function updateVisitTitleByPath(path: string, title: string): void {
  if (!path || !title) return
  const key = storageKey()
  if (!key) return
  const list = readStoredVisits(key)
  const basePath = normalizePath(path)
  const idx = list.findIndex(it => normalizePath(it.path) === basePath)
  if (idx >= 0) {
    list[idx].title = title
    writeRaw(list, key)
  }
}

export function updateVisitTitleById(id: Id | null | undefined, title: string): void {
  if (id == null || !title) return
  const key = storageKey()
  if (!key) return
  const list = readStoredVisits(key)
  const idx = list.findIndex(it => String(it.id || '') === String(id))
  if (idx >= 0) {
    list[idx].title = title
    writeRaw(list, key)
  }
}

export function updateVisitSectionById(
  id: Id | null | undefined,
  sectionId: Id | undefined,
  sectionName: string | undefined,
): void {
  if (id == null) return
  const key = storageKey()
  if (!key) return
  const list = readStoredVisits(key)
  const idx = list.findIndex(it => String(it.id || '') === String(id))
  if (idx >= 0) {
    if (sectionId != null) list[idx].sectionId = sectionId
    if (sectionName) list[idx].sectionName = sectionName
    writeRaw(list, key)
  }
}

export function initRecentVisitsTracking(router: Router | null | undefined): void {
  if (!router || typeof router.afterEach !== 'function') return
  // 避免重复注册
  if (window.__recent_visits_hook_installed) return
  window.__recent_visits_hook_installed = true
  router.afterEach((to) => {
    try {
      // 仅记录允许的路由（帖子详情）
      const rname = String((to.name as RouteRecordNameGeneric) || '')
      if (!ALLOWED_ROUTE_NAMES.has(rname)) return
      const path = normalizePath(to.fullPath || to.path || '')
      const name = String(to.name || '')
      const id = (to.params && (to.params.id || to.params.threadId)) as Id | null
      // 优先 meta.title，其次 document.title
      const title = String((to.meta && to.meta.title) || document.title || String(name || path))
      // sectionId 暂不可从路由直接获取，详情页加载后会补全
      addVisit({ path, name, title, id })
    } catch (_) {}
  })
}
