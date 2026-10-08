import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import Pagination from '../Pagination.vue'

describe('Pagination', () => {
  it('renders page info', () => {
    const wrapper = mount(Pagination, {
      props: { page: 2, size: 10, total: 25 },
    })
    expect(wrapper.text()).toContain('共 25 条 · 每页 10 条')
    expect(wrapper.text()).toContain('第')
    expect(wrapper.text()).toContain('/ 3 页')
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('2')
  })

  it('emits update:page when submitting page input', async () => {
    const wrapper = mount(Pagination, {
      props: { page: 2, size: 10, total: 25 },
    })
    const input = wrapper.find('input')
    await input.setValue('3')
    await input.trigger('keyup.enter')
    expect(wrapper.emitted('update:page')).toEqual([[3]])
  })

  it('emits update:page when clicking next/prev', async () => {
    const wrapper = mount(Pagination, {
      props: { page: 2, size: 10, total: 25 },
    })

    const buttons = wrapper.findAll('button')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')

    expect(wrapper.emitted('update:page')).toEqual([[1], [3]])
  })
})
