import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { computed, defineComponent, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ThreadDetail from '../ThreadDetail.vue'
import { useThreadDetailPage } from '@/pages/thread-detail/useThreadDetailPage'
import type { Thread } from '@/types'

const mocks = vi.hoisted(() => ({
  focusComposer: vi.fn(),
}))

vi.mock('@/pages/thread-detail/useThreadDetailPage', () => ({
  useThreadDetailPage: vi.fn(),
}))

function thread(): Thread {
  return {
    id: 1,
    title: '测试帖子',
    content: '帖子内容',
    sectionId: 1,
    createdAt: '2026-10-18T00:00:00Z',
  }
}

const CommentsStub = defineComponent({
  template: '<section id="thread-comments" />',
  setup(_, { expose }) {
    expose({ focusComposer: mocks.focusComposer })
    return {}
  },
})

async function createWrapper() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/threads/:id', name: 'thread-detail', component: ThreadDetail }],
  })
  await router.push('/threads/1')
  await router.isReady()
  const wrapper = mount(ThreadDetail, {
    global: {
      plugins: [router],
      stubs: {
        Comments: CommentsStub,
        ThreadDetailHeader: { template: '<header />' },
        ThreadDetailSummary: { template: '<section />' },
        ThreadDetailContent: { template: '<section />' },
        ThreadDetailActions: { template: '<section />' },
      },
    },
  })
  await flushPromises()
  return { router, wrapper }
}

describe('ThreadDetail mobile reply bar', () => {
  beforeEach(() => {
    mocks.focusComposer.mockReset()
    vi.mocked(useThreadDetailPage).mockReturnValue({
      anchorPostId: ref(null),
      displayAuthorAvatarUrl: computed(() => ''),
      error: ref(''),
      load: vi.fn().mockResolvedValue(undefined),
      loading: ref(false),
      thread: ref(thread()),
      updateThread: vi.fn(),
      updateAnchorFromHash: vi.fn(),
    })
  })

  it('offers comment navigation without changing the route and delegates reply focus', async () => {
    const { router, wrapper } = await createWrapper()
    const root = wrapper.get('#thread-comments')
    const scrollIntoView = vi.fn()
    Object.defineProperty(root.element, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    })
    const getElementSpy = vi.spyOn(document, 'getElementById').mockReturnValue(root.element as HTMLElement)
    const initialPath = router.currentRoute.value.fullPath

    await wrapper.get('.mobile-thread-reply button').trigger('click')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    expect(router.currentRoute.value.fullPath).toBe(initialPath)

    await wrapper.findAll('.mobile-thread-reply button')[1].trigger('click')
    expect(mocks.focusComposer).toHaveBeenCalledTimes(1)
    getElementSpy.mockRestore()
    wrapper.unmount()
  })

  it('renders the mobile reply bar with two 48px controls and desktop hiding rule', async () => {
    const { wrapper } = await createWrapper()
    const bar = wrapper.get('.mobile-thread-reply')
    expect(bar.findAll('button')).toHaveLength(2)
    expect(bar.text()).toContain('查看评论')
    expect(bar.text()).toContain('写回复')
    wrapper.unmount()
  })
})
