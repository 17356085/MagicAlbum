import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SidebarRight from '../SidebarRight.vue'
import { listPopularTags } from '@/api/tags'
import type { TagStats } from '@/types'

vi.mock('@/api/tags', () => ({
  listPopularTags: vi.fn(),
}))

const listPopularTagsMock = vi.mocked(listPopularTags)

function createWrapper() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/discover' },
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
    ],
  })
  const wrapper = mount(SidebarRight, {
    global: {
      plugins: [router],
    },
  })
  return { wrapper, router }
}

const tags: TagStats[] = [
  { id: 1, name: '摄影', threadCount: 32 },
  { id: 2, name: '旅行', threadCount: 18 },
]

describe('SidebarRight popular topics', () => {
  beforeEach(() => {
    listPopularTagsMock.mockReset()
  })

  it('renders API tags, real thread counts, and discover links', async () => {
    listPopularTagsMock.mockResolvedValueOnce(tags)
    const { wrapper } = createWrapper()

    expect(wrapper.get('[role="status"]').text()).toContain('加载中')
    await flushPromises()

    expect(listPopularTagsMock).toHaveBeenCalledWith()
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.text()).toContain('#摄影')
    expect(wrapper.text()).toContain('32')
    expect(wrapper.text()).toContain('#旅行')
    expect(wrapper.text()).toContain('18')
    expect(wrapper.text()).not.toContain('近期摄影打卡')
    expect(wrapper.find('a[href="#"]').exists()).toBe(false)
    expect(wrapper.get('button[type="button"]').text()).toContain('找小祥聊聊')

    const photographyLink = wrapper.findAll('a').find((link) => link.text().includes('摄影'))
    expect(photographyLink).toBeDefined()
    const url = new URL(photographyLink!.attributes('href')!, 'http://localhost')
    expect(url.pathname).toBe('/discover')
    expect(url.searchParams.get('tag')).toBe('摄影')
  })

  it('shows an explicit empty state without placeholder topics', async () => {
    listPopularTagsMock.mockResolvedValueOnce([])
    const { wrapper } = createWrapper()

    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toContain('暂无热门话题')
    expect(wrapper.findAll('li')).toHaveLength(0)
    expect(wrapper.find('a[href="#"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('近期摄影打卡')
  })

  it('shows an error instead of an empty list and retries successfully', async () => {
    listPopularTagsMock
      .mockRejectedValueOnce(new Error('network failure'))
      .mockResolvedValueOnce([{ id: 3, name: '夜景', threadCount: 7 }])
    const { wrapper } = createWrapper()

    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('热门话题加载失败')
    expect(wrapper.text()).not.toContain('暂无热门话题')

    const retryButton = alert.get('button')
    expect(retryButton.attributes('type')).toBe('button')
    await retryButton.trigger('click')
    await flushPromises()

    expect(listPopularTagsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('#夜景')
    expect(wrapper.text()).toContain('7')
  })

  it('does not update state when the request resolves after unmount', async () => {
    let resolveTags!: (value: TagStats[]) => void
    listPopularTagsMock.mockReturnValueOnce(new Promise<TagStats[]>((resolve) => {
      resolveTags = resolve
    }))
    const { wrapper } = createWrapper()
    const vm = wrapper.vm as unknown as {
      popularTags: TagStats[]
      tagsLoading: boolean
      tagsError: string
    }

    expect(vm.tagsLoading).toBe(true)
    wrapper.unmount()
    resolveTags([{ id: 4, name: '卸载后返回', threadCount: 1 }])
    await flushPromises()

    expect(vm.popularTags).toEqual([])
    expect(vm.tagsLoading).toBe(true)
    expect(vm.tagsError).toBe('')
  })
})
