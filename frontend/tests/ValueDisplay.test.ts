import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ValueDisplay from '@/components/ValueDisplay.vue'
import StatusTag from '@/components/StatusTag.vue'

describe('共享状态与空值组件', () => {
  it('零值使用正常文字且不落入空态样式', () => {
    const wrapper = mount(ValueDisplay, { props: { value: 0, unit: '门' } })
    expect(wrapper.text()).toBe('0 门')
    expect(wrapper.classes()).toContain('value-zero')
    expect(wrapper.classes()).not.toContain('value-empty')
  })

  it('空值与不适用显示不同文案', async () => {
    const wrapper = mount(ValueDisplay, { props: { value: null } })
    expect(wrapper.text()).toBe('未提供')
    await wrapper.setProps({ raw: '—' })
    expect(wrapper.text()).toBe('不适用')
    expect(wrapper.classes()).toContain('value-na')
  })

  it('状态同时提供文字而非仅依赖颜色', () => {
    const wrapper = mount(StatusTag, { props: { status: 'RECOGNIZED_UNMET' }, global: { stubs: { 'el-tag': { template: '<span><slot /></span>' } } } })
    expect(wrapper.text()).toBe('已认定未达标')
  })
})
