import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AuthModal from '../AuthModal.vue'
import { useAuthStore } from '@/stores/auth'

const oauth = vi.hoisted(() => ({ start: vi.fn() }))
vi.mock('@/services/thirdPartyAuth', () => ({ startOAuthAuthorize: oauth.start }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

const ThirdPartyStub = defineComponent({
  emits: ['select'],
  template: '<button data-test="oauth" type="button" @click="$emit(\'select\', \'google\')">OAuth</button>',
})

function mountModal() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(AuthModal, {
    global: {
      plugins: [pinia],
      stubs: {
        AuthLoginForm: true,
        AuthQrLogin: true,
        AuthRegisterForm: true,
        AuthThirdPartyLogin: ThirdPartyStub,
      },
    },
  })
}

describe('AuthModal OAuth lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('does not show a late result after the modal closes', async () => {
    const pending = deferred<{ ok: boolean; message: string }>()
    oauth.start.mockReturnValueOnce(pending.promise)
    const wrapper = mountModal()

    await wrapper.get('[data-test="oauth"]').trigger('click')
    await wrapper.get('button[aria-label="关闭"]').trigger('click')
    pending.resolve({ ok: true, message: '正在跳转到 Google 授权页' })
    await flushPromises()

    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('invalidates a pending password or code login before starting OAuth', async () => {
    oauth.start.mockResolvedValueOnce({ ok: false, message: '登录准备中' })
    const wrapper = mountModal()
    const auth = useAuthStore()
    const previous = auth.getAuthGeneration()
    await wrapper.get('[data-test="oauth"]').trigger('click')
    expect(auth.isAuthGenerationCurrent(previous)).toBe(false)
    expect(oauth.start).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('keeps only the latest OAuth request result', async () => {
    const first = deferred<{ ok: boolean; message: string }>()
    oauth.start
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce({ ok: false, message: '第二次请求失败' })
    const wrapper = mountModal()

    await wrapper.get('[data-test="oauth"]').trigger('click')
    await wrapper.get('[data-test="oauth"]').trigger('click')
    first.resolve({ ok: true, message: '第一次请求不应显示' })
    await flushPromises()

    expect(wrapper.find('[role="status"]').text()).toBe('第二次请求失败')
    wrapper.unmount()
  })
})
