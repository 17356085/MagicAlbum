import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import api from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import Comments from '../Comments.vue'
import CommentsComposer from '../comments/CommentsComposer.vue'

const mocks = vi.hoisted(() => ({ listPosts: vi.fn(), createPost: vi.fn(), likePost: vi.fn(), unlikePost: vi.fn() }))
vi.mock('@/api/posts', () => mocks)

const originalAdapter = api.defaults.adapter

describe('comment upload transport outcomes', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    for (const mock of Object.values(mocks)) mock.mockReset()
    mocks.listPosts.mockResolvedValue({ items: [], page: 1, size: 15, total: 0 })
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    localStorage.clear()
  })

  it('retains the draft and warns about a possibly completed upload after a timeout', async () => {
    let posts = 0
    api.defaults.adapter = async () => {
      posts += 1
      throw new AxiosError('timeout', 'ECONNABORTED')
    }
    const wrapper = mount(Comments, {
      props: { threadId: 1 },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    try {
      await flushPromises()
      const composer = wrapper.findComponent(CommentsComposer)
      composer.vm.$emit('update:modelValue', '保留我的评论草稿')
      const input = composer.get('input[type="file"]')
      Object.defineProperty(input.element, 'files', {
        configurable: true,
        value: [new File(['image'], 'comment.png', { type: 'image/png' })],
      })
      await input.trigger('change')
      await flushPromises()
      expect(posts).toBe(1)
      expect(mocks.createPost).not.toHaveBeenCalled()
      expect(composer.props('modelValue')).toBe('保留我的评论草稿')
      expect(wrapper.get('[role="alert"]').text()).toContain('图片上传结果未确认')
      expect(wrapper.get('[role="alert"]').text()).toContain('重复上传可能产生多份图片')
    } finally {
      wrapper.unmount()
    }
  })
})
