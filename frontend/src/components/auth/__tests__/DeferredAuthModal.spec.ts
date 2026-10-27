import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DeferredAuthModal from '../DeferredAuthModal.vue'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const fakeAuthModal = defineComponent({
  name: 'FakeAuthModal',
  emits: ['close', 'success'],
  setup(_, { emit }) {
    const panel = ref<HTMLElement | null>(null)
    let previousFocus: HTMLElement | null = null

    onMounted(() => {
      previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
      void nextTick(() => panel.value?.focus())
    })

    onBeforeUnmount(() => {
      if (previousFocus?.isConnected) previousFocus.focus()
    })

    return { emit, panel }
  },
  template: `
    <div ref="panel" data-test="real-auth-modal" role="dialog" tabindex="-1">
      <button type="button" data-test="real-close" @click="emit('close')">close</button>
      <button type="button" data-test="real-success" @click="emit('success')">success</button>
    </div>
  `,
})

type AuthModalModule = { default: typeof fakeAuthModal }

function createTrigger(): HTMLButtonElement {
  const trigger = document.createElement('button')
  trigger.type = 'button'
  trigger.textContent = 'open'
  document.body.append(trigger)
  trigger.focus()
  return trigger
}

function mountDeferred(loader: () => Promise<AuthModalModule>) {
  return mount(DeferredAuthModal, { attachTo: document.body, props: { loader } })
}

describe('DeferredAuthModal', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('starts loading only after mount and shows an accessible pending dialog', async () => {
    const trigger = createTrigger()
    const pending = deferred<AuthModalModule>()
    const loader = vi.fn(() => pending.promise)

    expect(loader).not.toHaveBeenCalled()
    const wrapper = mountDeferred(loader)
    expect(loader).toHaveBeenCalledOnce()
    await nextTick()

    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(dialog.get('[role="status"]').text()).toContain('正在加载')
    expect(dialog.find('button[aria-label="关闭"]').exists()).toBe(true)
    expect(document.activeElement).toBe(dialog.get('button[aria-label="关闭"]').element)

    pending.resolve({ default: fakeAuthModal })
    await flushPromises()
    await nextTick()
    expect(wrapper.get('[data-test="real-auth-modal"]')).toBeTruthy()
    wrapper.unmount()
    expect(document.activeElement).toBe(trigger)
  })

  it('shows a failure alert and retries once without parallel duplicate loads', async () => {
    createTrigger()
    const retryPending = deferred<AuthModalModule>()
    const loader = vi.fn()
      .mockRejectedValueOnce(new Error('chunk unavailable'))
      .mockReturnValueOnce(retryPending.promise)
    const wrapper = mountDeferred(loader)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('加载失败')
    const retry = wrapper.get('button.ui-button-primary')
    await retry.trigger('click')
    expect(document.activeElement).toBe(wrapper.get('button[aria-label="关闭"]').element)
    await retry.trigger('click')
    expect(loader).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')

    retryPending.resolve({ default: fakeAuthModal })
    await flushPromises()
    await nextTick()
    expect(wrapper.get('[data-test="real-auth-modal"]')).toBeTruthy()
    wrapper.unmount()
  })

  it('closes during a pending load and ignores the late module result', async () => {
    const trigger = createTrigger()
    const pending = deferred<AuthModalModule>()
    const loader = vi.fn(() => pending.promise)
    const wrapper = mountDeferred(loader)
    await nextTick()

    await wrapper.get('button[aria-label="关闭"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    pending.resolve({ default: fakeAuthModal })
    await flushPromises()

    expect(wrapper.find('[data-test="real-auth-modal"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger)
    wrapper.unmount()
  })

  it('closes from Escape and traps fallback focus', async () => {
    createTrigger()
    const pending = deferred<AuthModalModule>()
    const wrapper = mountDeferred(() => pending.promise)
    await nextTick()

    const dialog = wrapper.get('[role="dialog"]')
    const close = dialog.get('button[aria-label="关闭"]')
    await dialog.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(close.element)

    await dialog.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('restores the original trigger after the loaded modal is torn down', async () => {
    const trigger = createTrigger()
    const loader = vi.fn().mockResolvedValue({ default: fakeAuthModal })
    const wrapper = mountDeferred(loader)
    await flushPromises()
    await nextTick()
    expect(document.activeElement).toBe(wrapper.get('[data-test="real-auth-modal"]').element)

    await wrapper.get('[data-test="real-close"]').trigger('click')
    expect(wrapper.find('[data-test="real-auth-modal"]').exists()).toBe(false)
    await nextTick()
    expect(document.activeElement).toBe(trigger)
    wrapper.unmount()
  })

  it('forwards success and close events from the loaded modal', async () => {
    const loader = vi.fn().mockResolvedValue({ default: fakeAuthModal })
    const wrapper = mountDeferred(loader)
    await flushPromises()

    await wrapper.get('[data-test="real-success"]').trigger('click')
    expect(wrapper.emitted('success')).toHaveLength(1)
    await wrapper.get('[data-test="real-close"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
