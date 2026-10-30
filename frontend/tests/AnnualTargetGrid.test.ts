import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AnnualTargetGrid from '@/components/AnnualTargetGrid.vue'
import type { AnnualTarget } from '@/types/api'

const editorStub = { name: 'TargetValueEditor', template: '<button class="target-editor" @click="$emit(\'update:value\', 0)">编辑</button>' }

describe('五年度目标网格', () => {
  it('补齐 2026—2030 五列并在编辑时保留其他年度', async () => {
    const wrapper = mount(AnnualTargetGrid, { props: { modelValue: [] as AnnualTarget[], startYear: 2026, endYear: 2030, unit: '项' }, global: { stubs: { TargetValueEditor: editorStub, 'el-input': true, 'el-checkbox': true, 'el-tag': true } } })
    expect(wrapper.findAll('.target-editor')).toHaveLength(5)
    await wrapper.find('.target-editor').trigger('click')
    const emitted = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as AnnualTarget[]
    expect(emitted.map((item) => item.target_year)).toEqual([2026, 2027, 2028, 2029, 2030])
    expect(emitted[0].professional_target_value).toBe(0)
    expect(emitted[1].professional_target_value).toBeUndefined()
  })

  it('用年度状态标签替代逐年确认复选框', () => {
    const wrapper = mount(AnnualTargetGrid, {
      props: { modelValue: [] as AnnualTarget[], startYear: 2026, endYear: 2030, showConfirm: true },
      global: { stubs: { TargetValueEditor: editorStub, 'el-input': true, 'el-tag': { template: '<span class="status-tag"><slot /></span>' } } },
    })
    expect(wrapper.findAll('el-checkbox-stub')).toHaveLength(0)
    expect(wrapper.findAll('.status-tag')).toHaveLength(5)
    expect(wrapper.text()).toContain('2027 · 待确认')
  })
})
