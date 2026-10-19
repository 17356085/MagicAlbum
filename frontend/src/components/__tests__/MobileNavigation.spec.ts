import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import MobileNavigation from '@/components/MobileNavigation.vue'

const page = { template: '<div />' }

async function createWrapper(path = '/discover') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', name: 'discover', component: page },
      { path: '/sections', name: 'sections', component: page },
      { path: '/feed', name: 'feed', component: page },
      { path: '/ranking', name: 'ranking', component: page },
      { path: '/me', name: 'me', component: page },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(MobileNavigation, { global: { plugins: [router] } })
  return { router, wrapper }
}

describe('MobileNavigation', () => {
  let wrapper: ReturnType<typeof mount> | null = null

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('exposes five destination links with a mobile navigation label', async () => {
    const result = await createWrapper('/sections')
    wrapper = result.wrapper

    expect(wrapper.get('nav').attributes('aria-label')).toBe('移动端导航')
    const links = wrapper.findAll('a')
    expect(links.map((link) => link.text())).toEqual(['发现', '分区', '动态', '排行', '我的'])
    expect(links.map((link) => link.attributes('href'))).toEqual([
      '/discover', '/sections', '/feed', '/ranking', '/me',
    ])
    expect(links[1].attributes('aria-current')).toBe('page')
  })

  it('marks only the current destination active', async () => {
    const result = await createWrapper('/me')
    wrapper = result.wrapper
    const links = wrapper.findAll('a')

    expect(links[4].attributes('aria-current')).toBe('page')
    expect(links.slice(0, 4).every((link) => !link.attributes('aria-current'))).toBe(true)
  })
})
