import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReportDialog from '../ReportDialog.vue'
import { useAuthStore } from '@/stores/auth'

type AdapterScenario = {
  status: number
  data: unknown
  reject?: boolean
  deferred?: boolean
}

const transport = vi.hoisted(() => {
  const state: {
    scenario: AdapterScenario
    requests: Array<{ url?: string; method?: string; data?: unknown }>
    resolveDeferred?: (response: AxiosResponse<unknown>) => void
  } = {
    scenario: { status: 201, data: null },
    requests: [],
  }

  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    state.requests.push({ url: config.url, method: config.method, data: config.data })
    const scenario = state.scenario
    const response: AxiosResponse<unknown> = {
      data: scenario.data,
      status: scenario.status,
      statusText: String(scenario.status),
      headers: {},
      config,
    }
    if (scenario.deferred) {
      return await new Promise<AxiosResponse<unknown>>((resolve) => {
        state.resolveDeferred = resolve
      })
    }
    if (scenario.reject) {
      throw Object.assign(new Error('Request failed with status code ' + scenario.status), {
        response,
        config,
      })
    }
    return response
  }

  return { state, adapter }
})

vi.mock('@/api/client', async () => {
  const axiosModule = await vi.importActual<typeof import('axios')>('axios')
  return { default: axiosModule.default.create({ baseURL: 'http://offline.invalid/api/v1', adapter: transport.adapter }) }
})

let wrapper: VueWrapper | undefined

function element<T extends Element>(selector: string): T {
  const found = document.body.querySelector<T>(selector)
  if (!found) throw new Error('Missing ' + selector)
  return found
}

function submit(): void {
  element<HTMLFormElement>('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
}

function requestPayload(index = -1): unknown {
  const requests = transport.state.requests
  const request = requests[index < 0 ? requests.length + index : index]
  return typeof request?.data === 'string' ? JSON.parse(request.data) : request?.data
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'real-token', user: { id: 1, username: 'alice' } })
  transport.state.scenario = { status: 201, data: null }
  transport.state.requests.length = 0
  transport.state.resolveDeferred = undefined
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
})

describe('ReportDialog real Axios transport and ACK handling', () => {
  it('keeps the form retryable after an exceptional 2xx body, then accepts one valid ACK with the same payload', async () => {
    transport.state.scenario = { status: 200, data: '<html>gateway error</html>' }
    wrapper = mount(ReportDialog, {
      props: { modelValue: true, targetType: 'POST', targetId: 22, isLoggedIn: true },
    })
    await flushPromises()
    const select = element<HTMLSelectElement>('select')
    select.value = 'HARASSMENT'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    const textarea = element<HTMLTextAreaElement>('textarea')
    textarea.value = '保留这段说明'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await wrapper.vm.$nextTick()

    submit()
    await flushPromises()

    expect(element('[role="alert"]').textContent).toContain('举报提交结果暂未确认，请重试')
    expect(element<HTMLButtonElement>('button[type="submit"]').disabled).toBe(false)
    expect(element<HTMLSelectElement>('select').value).toBe('HARASSMENT')
    expect(element<HTMLTextAreaElement>('textarea').value).toBe('保留这段说明')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(requestPayload()).toEqual({
      targetType: 'POST',
      targetId: 22,
      reason: 'HARASSMENT',
      detail: '保留这段说明',
    })

    transport.state.scenario = {
      status: 201,
      data: {
        id: 73,
        targetType: 'POST',
        targetId: 22,
        reporterId: 1,
        reason: 'SPAM',
        detail: '旧持久化说明',
        status: 'HIDDEN',
      },
    }
    submit()
    await flushPromises()

    expect(transport.state.requests).toHaveLength(2)
    expect(requestPayload(0)).toEqual(requestPayload(1))
    expect(element('[role="status"]').textContent).toContain('举报已提交')
    expect(element<HTMLButtonElement>('button[type="submit"]').disabled).toBe(true)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('preserves the existing HTTP error and current account instead of treating it as an ACK error', async () => {
    transport.state.scenario = {
      status: 503,
      data: { message: '服务暂不可用' },
      reject: true,
    }
    wrapper = mount(ReportDialog, {
      props: { modelValue: true, targetType: 'THREAD', targetId: 7, isLoggedIn: true },
    })
    await flushPromises()

    submit()
    await flushPromises()

    expect(element('[role="alert"]').textContent).toContain('服务暂不可用')
    expect(element<HTMLButtonElement>('button[type="submit"]').disabled).toBe(false)
    expect(useAuthStore().token).toBe('real-token')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('closes and ignores a late ACK when the authenticated account changes', async () => {
    transport.state.scenario = {
      status: 201,
      data: { id: 74, targetType: 'THREAD', targetId: 7 },
      deferred: true,
    }
    wrapper = mount(ReportDialog, {
      props: { modelValue: true, targetType: 'THREAD', targetId: 7, isLoggedIn: true },
    })
    await flushPromises()
    submit()
    await flushPromises()
    expect(transport.state.requests).toHaveLength(1)

    useAuthStore().applyAuthState({ accessToken: 'other-token', user: { id: 2, username: 'bob' } })
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])

    transport.state.resolveDeferred?.({
      data: { id: 74, targetType: 'THREAD', targetId: 7 },
      status: 201,
      statusText: '201',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
    })
    await flushPromises()
    expect(document.body.querySelector('[role="status"]')).toBeNull()
  })
})
