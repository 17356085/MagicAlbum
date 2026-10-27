import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import RecentVisitsPanel from '@/components/sidebar/RecentVisitsPanel.vue'
import RecentHistory from '@/pages/RecentHistory.vue'
import { persistAuthState } from '@/utils/authStorage'
import {
  addVisit, getAllRecentVisits, getRecentVisits, pruneExpired,
  removeRecentVisit, updateVisitTitleById,
} from '../useRecentVisits'

vi.mock('@/api/sections', () => ({
  listSections: async () => ({ items: [], page: 1, size: 200, total: 0 }),
}))

const now = Date.parse('2026-10-26T00:00:00Z')
const key = 'recent_visits_v1:7'
const saved = { path: '/threads/11', title: '有效浏览记录', id: 11, ts: now - 60000 }
const wrappers: VueWrapper[] = []

function cache(value: unknown): string {
  const raw = JSON.stringify(value)
  localStorage.setItem(key, raw)
  return raw
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.spyOn(Date, 'now').mockReturnValue(now)
  persistAuthState({ accessToken: 'cache-owner-token', user: { id: 7, username: 'owner-seven' } })
})

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
})

describe('recent visit cache boundary recovery', () => {
  it('reads valid entries from a mixed cache without throwing or rewriting storage', () => {
    const raw = cache([null, false, [], 42, saved, { path: null, ts: now }, { path: '/threads/bad', ts: 'bad' }])
    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    try {
      expect(getRecentVisits()).toEqual([saved])
      expect(getAllRecentVisits()).toEqual([saved])
      expect(localStorage.getItem(key)).toBe(raw)
      expect(updated).not.toHaveBeenCalled()
    } finally { window.removeEventListener('recent-visits-updated', updated) }
  })

  it('drops invalid optional metadata while keeping a usable path and numeric legacy timestamp', () => {
    cache([{ ...saved, ts: String(saved.ts), id: {}, title: {}, name: 'thread-detail', sectionId: [], sectionName: false, extra: 'discard' }])
    expect(getRecentVisits()).toEqual([{ path: saved.path, ts: saved.ts, name: 'thread-detail' }])
  })

  it('keeps supported id forms and old paths instead of inventing a route migration', () => {
    const old = { ...saved, path: '/threads/old?mode=legacy#post-1', id: 'old', sectionId: 'community', sectionName: '旧分区' }
    cache([old])
    expect(getAllRecentVisits()).toEqual([old])
  })

  it('ignores objects without a usable path or representable timestamp', () => {
    cache([null, {}, { path: '', ts: now }, { path: {}, ts: now }, { path: '/threads/12', ts: 1e20 }, { path: '/threads/13', ts: null }])
    expect(getRecentVisits()).toEqual([])
  })

  it('preserves existing expiry and future-clock behavior', () => {
    const boundary = { ...saved, id: 12, ts: now - 90 * 86400000 }
    const expired = { ...saved, id: 13, ts: boundary.ts - 1 }
    const future = { ...saved, id: 14, ts: now + 3600000 }
    cache([boundary, expired, future])
    expect(getAllRecentVisits()).toEqual([boundary, future])
    expect(getRecentVisits(1)).toEqual([boundary])
  })

  it('compacts malformed entries during pruning without changing another owner key', () => {
    cache([null, saved])
    localStorage.setItem('recent_visits_v1:8', '[null]')
    pruneExpired()
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual([saved])
    expect(localStorage.getItem('recent_visits_v1:8')).toBe('[null]')
  })

  it('keeps new visits, title updates and removals usable beside damaged cached data', () => {
    cache([null, { path: 42, ts: now }, saved])
    addVisit({ path: '/threads/22?draft=1#post', title: '新增记录', id: 22 })
    expect(getAllRecentVisits()).toEqual([
      { path: '/threads/22', title: '新增记录', id: 22, ts: now }, saved,
    ])
    updateVisitTitleById(11, '更新标题')
    removeRecentVisit({ path: '/threads/22', id: 22, ts: now })
    expect(getAllRecentVisits()).toEqual([{ ...saved, title: '更新标题' }])
  })

  it('still treats malformed JSON and non-array documents as empty', () => {
    for (const raw of ['{broken', 'null', '{"path":"/threads/11"}']) {
      localStorage.setItem(key, raw)
      expect(getRecentVisits()).toEqual([])
    }
  })

  async function mountEntry(component: typeof RecentVisitsPanel | typeof RecentHistory): Promise<VueWrapper> {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
      { path: '/history', component: { template: '<div />' } },
      { path: '/threads/:id', component: { template: '<div />' } },
    ] })
    await router.push('/discover')
    const wrapper = mount(component, { global: { plugins: [createPinia(), router] } })
    wrappers.push(wrapper)
    await flushPromises()
    return wrapper
  }

  it('renders the real sidebar panel with a mixed cache', async () => {
    cache([null, saved])
    const wrapper = await mountEntry(RecentVisitsPanel)
    expect(wrapper.text()).toContain('有效浏览记录')
    expect(wrapper.get('a[href="/threads/11"]').text()).toContain('有效浏览记录')
  })

  it('renders the real history page and preserves valid entries after its initial prune', async () => {
    cache([null, saved])
    const wrapper = await mountEntry(RecentHistory)
    expect(wrapper.text()).toContain('有效浏览记录')
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual([saved])
  })
})
