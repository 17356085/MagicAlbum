// 最近浏览：基于路由 afterEach 记录本地 localStorage，并提供读取 API
// 存储结构：[{ path, name, title, id, sectionId, sectionName, ts }]
// 保留最近 10 条，时间窗口默认 7 天；去重按 path

const STORAGE_KEY = 'recent_visits_v1'
const MAX_ITEMS = 10
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000 // 7 天
// 仅记录“文章/帖子详情”路由
const ALLOWED_ROUTE_NAMES = new Set(['thread-detail'])

function normalizePath(path) {
  const p = String(path || '')
  // 去除 query 与 hash，保持基础路径用于去重与展示
  return p.replace(/[?#].*$/, '')
}

function readRaw() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch (_) {
    return []
  }
}

function writeRaw(list) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(list)) } catch (_) {}
  // 触发事件，便于侧栏刷新
  try { window.dispatchEvent(new CustomEvent('recent-visits-updated')) } catch (_) {}
}

export function addVisit({ path, name, title, id, sectionId, sectionName }) {
  const ts = Date.now()
  const list = readRaw()
  const basePath = normalizePath(path)
  // 去重：优先按帖子 ID；无 ID 时按基础 path
  const filtered = list.filter(it => {
    if (!it) return false
    const sameId = (id != null) && (String(it.id || '') === String(id))
    const samePath = String(normalizePath(it.path || '')) === String(basePath)
    return !(sameId || (!id && samePath))
  })
  filtered.unshift({ path: basePath, name, title, id, sectionId, sectionName, ts })
  // 过滤过期项与限制长度
  const recent = filtered.filter(it => (ts - Number(it.ts || 0)) <= MAX_AGE_MS).slice(0, MAX_ITEMS)
  writeRaw(recent)
}

export function getRecentVisits(limit = 5) {
  const now = Date.now()
  const list = readRaw()
  const recent = list.filter(it => (now - Number(it.ts || 0)) <= MAX_AGE_MS)
  return recent.slice(0, limit)
}

export function getAllRecentVisits() {
  const now = Date.now()
  const list = readRaw()
  return list.filter(it => (now - Number(it.ts || 0)) <= MAX_AGE_MS)
}

export function clearAllRecentVisits() {
  try { localStorage.removeItem(STORAGE_KEY) } catch (_) {}
  writeRaw([])
}

export function pruneExpired() {
  const now = Date.now()
  const list = readRaw()
  const pruned = list.filter(it => (now - Number(it.ts || 0)) <= MAX_AGE_MS)
  writeRaw(pruned.slice(0, MAX_ITEMS))
}

export function updateVisitTitleByPath(path, title) {
  if (!path || !title) return
  const list = readRaw()
  const basePath = normalizePath(path)
  const idx = list.findIndex(it => it && normalizePath(it.path) === basePath)
  if (idx >= 0) {
    list[idx].title = title
    writeRaw(list)
  }
}

export function updateVisitTitleById(id, title) {
  if (id == null || !title) return
  const list = readRaw()
  const idx = list.findIndex(it => it && String(it.id || '') === String(id))
  if (idx >= 0) {
    list[idx].title = title
    writeRaw(list)
  }
}

export function updateVisitSectionById(id, sectionId, sectionName) {
  if (id == null) return
  const list = readRaw()
  const idx = list.findIndex(it => it && String(it.id || '') === String(id))
  if (idx >= 0) {
    if (sectionId != null) list[idx].sectionId = sectionId
    if (sectionName) list[idx].sectionName = sectionName
    writeRaw(list)
  }
}

export function initRecentVisitsTracking(router) {
  if (!router || typeof router.afterEach !== 'function') return
  // 避免重复注册
  if (window.__recent_visits_hook_installed) return
  window.__recent_visits_hook_installed = true
  router.afterEach((to) => {
    try {
      // 仅记录允许的路由（帖子详情）
      const rname = String(to.name || '')
      if (!ALLOWED_ROUTE_NAMES.has(rname)) return
      const path = normalizePath(to.fullPath || to.path || '')
      const name = to.name || ''
      const id = to.params && (to.params.id || to.params.threadId) || null
      // 优先 meta.title，其次 document.title
      const title = (to.meta && to.meta.title) || document.title || String(name || path)
      // sectionId 暂不可从路由直接获取，详情页加载后会补全
      addVisit({ path, name, title, id })
    } catch (_) {}
  })
}