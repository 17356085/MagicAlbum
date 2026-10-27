import { createPinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import productionRouter from '@/router'
import { useAuthStore } from '@/stores/auth'

const api = vi.hoisted(() => ({ me: vi.fn(), reports: vi.fn(), users: vi.fn(), sections: vi.fn(), audit: vi.fn() }))
vi.mock('@/api/admin', () => ({ listAdminReports: api.reports, resolveAdminReport: vi.fn(), validateAdminReportReason: (reason: string) => reason.trim() }))
vi.mock('@/api/governance', () => ({
  getGovernanceMe: api.me, listGovernanceUsers: api.users, listGovernanceSections: api.sections, listGovernanceAudit: api.audit,
  createGovernanceSection: vi.fn(), updateGovernanceSection: vi.fn(), updateGovernanceUserAdmin: vi.fn(),
  updateGovernanceModeratorSections: vi.fn(), updateGovernancePostingRestriction: vi.fn(),
  requireGovernanceReason: (reason: string) => reason.trim(),
}))
vi.mock('@/composables/useLiveNotifications', () => ({ useLiveNotifications: vi.fn() }))
vi.mock('@/composables/useMediaProcessing', () => ({ useMediaProcessing: vi.fn() }))

let wrapper: VueWrapper | undefined
beforeEach(() => {
  localStorage.clear()
  Object.values(api).forEach(mock => mock.mockReset())
  api.me.mockResolvedValue({ admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null })
  for (const mock of [api.reports, api.users, api.audit]) mock.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  api.sections.mockResolvedValue([])
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('App and admin page refresh ownership', () => {
  it.each(['/admin/reports', '/admin/community'])('verifies each account once across the App remount at %s', async path => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.applyAuthState({ accessToken: 'admin-a', user: { id: 1, username: 'A' } })
    const router = createRouter({ history: createMemoryHistory(), routes: productionRouter.options.routes })
    await router.push(path)
    await router.isReady()
    wrapper = mount(App, { global: {
      plugins: [pinia, router],
      stubs: { AppHeader: true, SidebarLeft: true, AiChat: true, MobileNavigation: true, MediaProcessingStatus: true },
    } })
    await flushPromises()
    expect(api.me).toHaveBeenCalledTimes(1)
    auth.applyAuthState({ accessToken: 'admin-b', user: { id: 2, username: 'B' } })
    await flushPromises()
    expect(api.me).toHaveBeenCalledTimes(2)
    if (path.endsWith('reports')) expect(api.reports).toHaveBeenCalledTimes(2)
    else {
      expect(api.users).toHaveBeenCalledTimes(2)
      expect(api.sections).toHaveBeenCalledTimes(2)
      expect(api.audit).toHaveBeenCalledTimes(2)
    }
    expect(wrapper.text()).not.toContain('正在验证')
  })
})
