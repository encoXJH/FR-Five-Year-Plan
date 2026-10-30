import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TargetValueEditor from '@/components/TargetValueEditor.vue'

const stubs = {
  'el-input-number': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="number-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value === \'\' ? undefined : Number($event.target.value))" />' },
  'el-select': { props: ['modelValue'], emits: ['change'], template: '<select class="mode-select" :value="modelValue" @change="$emit(\'change\', $event.target.value)"><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="number-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-radio-button': { template: '<button><slot /></button>' },
}

describe('目标值编辑组件', () => {
  it('保留数值零并同步原文', async () => {
    const wrapper = mount(TargetValueEditor, { props: { value: 0, rawValue: '0', unit: '个' }, global: { stubs } })
    expect((wrapper.find('.number-input').element as HTMLInputElement).value).toBe('0')
    await wrapper.find('.number-input').setValue('2')
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual(['2'])
    expect(wrapper.emitted('update:rawValue')?.at(-1)).toEqual(['2'])
  })

  it('从不适用切回填写值时不会被旧原文 watch 重置', async () => {
    const wrapper = mount(TargetValueEditor, { props: { value: null, rawValue: '—', boolValue: null }, global: { stubs } })
    expect((wrapper.find('.mode-select').element as HTMLSelectElement).value).toBe('NA')
    await wrapper.find('.mode-select').setValue('VALUE')
    await wrapper.setProps({ value: null, textValue: null, boolValue: null, rawValue: null })
    await wrapper.vm.$nextTick()
    expect((wrapper.find('.mode-select').element as HTMLSelectElement).value).toBe('VALUE')
    expect(wrapper.find('.number-input').exists()).toBe(true)
  })

  it('选择填写值后输入数字，失焦和 props 同步后仍保留', async () => {
    const wrapper = mount(TargetValueEditor, { props: { value: null, rawValue: null, boolValue: null }, global: { stubs } })
    expect((wrapper.find('.mode-select').element as HTMLSelectElement).value).toBe('EMPTY')
    await wrapper.find('.mode-select').setValue('VALUE')
    await wrapper.find('.number-input').setValue('4')
    await wrapper.setProps({ value: 4, rawValue: '4' })
    await wrapper.find('.number-input').trigger('blur')
    expect((wrapper.find('.mode-select').element as HTMLSelectElement).value).toBe('VALUE')
    expect((wrapper.find('.number-input').element as HTMLInputElement).value).toBe('4')
  })

  it('选择填写值后输入 0，失焦后仍保留且不切回未提供', async () => {
    const wrapper = mount(TargetValueEditor, { props: { value: null, rawValue: null }, global: { stubs } })
    await wrapper.find('.mode-select').setValue('VALUE')
    await wrapper.find('.number-input').setValue('0')
    await wrapper.setProps({ value: 0, rawValue: '0' })
    await wrapper.find('.number-input').trigger('blur')
    expect((wrapper.find('.mode-select').element as HTMLSelectElement).value).toBe('VALUE')
    expect((wrapper.find('.number-input').element as HTMLInputElement).value).toBe('0')
  })

  it('false、文本与不适用分别得到正确模式', () => {
    const booleanWrapper = mount(TargetValueEditor, { props: { valueKind: 'BOOLEAN', boolValue: false, rawValue: '否' }, global: { stubs } })
    expect((booleanWrapper.find('.mode-select').element as HTMLSelectElement).value).toBe('VALUE')
    const textWrapper = mount(TargetValueEditor, { props: { valueKind: 'RATIO', textValue: '1:350', rawValue: '1:350' }, global: { stubs } })
    expect((textWrapper.find('.mode-select').element as HTMLSelectElement).value).toBe('VALUE')
    const naWrapper = mount(TargetValueEditor, { props: { rawValue: '—' }, global: { stubs } })
    expect(naWrapper.find('.semantic-placeholder').text()).toBe('不适用')
    expect(naWrapper.find('.number-input').exists()).toBe(false)
  })

  it('切换不适用会清空各值字段但保留语义原文', async () => {
    const wrapper = mount(TargetValueEditor, { props: { value: 3, rawValue: '3' }, global: { stubs } })
    await wrapper.find('.mode-select').setValue('NA')
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual([null])
    expect(wrapper.emitted('update:textValue')?.at(-1)).toEqual([null])
    expect(wrapper.emitted('update:boolValue')?.at(-1)).toEqual([null])
    expect(wrapper.emitted('update:rawValue')?.at(-1)).toEqual(['—'])
  })
})
