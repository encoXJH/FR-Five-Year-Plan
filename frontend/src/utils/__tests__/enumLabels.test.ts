import { describe, expect, it } from 'vitest'
import { enumLabel, enumOptions, FALLBACK_ENUM_LABELS, submissionStatusLabel } from '../enumLabels'
import type { EnumDict } from '@/types/enums'

describe('enumLabel', () => {
  it('字典优先', () => {
    const dict: EnumDict = { PlanStatus: { ACTIVE: '自定义执行中' } }
    expect(enumLabel('PlanStatus', 'ACTIVE', dict)).toBe('自定义执行中')
  })

  it('字典缺失时用兜底表', () => {
    expect(enumLabel('StatCaliber', 'INCREMENT')).toBe('增量累计型')
    expect(enumLabel('PlanStatus', 'ARCHIVED', {})).toBe('已归档')
  })

  it('未知枚举值返回原值，不报错', () => {
    expect(enumLabel('PlanStatus', 'SOME_NEW_VALUE')).toBe('SOME_NEW_VALUE')
    expect(enumLabel('UnknownKind', 'X')).toBe('X')
  })
})

describe('两级审核状态标签', () => {
  it('ReviewNode 提供学院负责人审核和校级负责人审核中文名', () => {
    expect(enumLabel('ReviewNode', 'UNIT_REVIEW')).toBe('学院负责人审核')
    expect(enumLabel('ReviewNode', 'LEADER_REVIEW')).toBe('校级负责人审核')
  })

  it('PENDING_REVIEW 与节点组合展示，不发明新的 SubmissionStatus', () => {
    expect(submissionStatusLabel('PENDING_REVIEW', 'UNIT_REVIEW')).toBe('待学院负责人审核')
    expect(submissionStatusLabel('PENDING_REVIEW', 'LEADER_REVIEW')).toBe('待校级负责人审核')
    expect(submissionStatusLabel('PENDING_REVIEW', null)).toBe('待审核')
    expect(submissionStatusLabel('APPROVED', 'LEADER_REVIEW')).toBe('已通过')
  })

  it('组合标签优先使用服务端字典', () => {
    const dict: EnumDict = { ReviewNode: { UNIT_REVIEW: '学院初审' } }
    expect(submissionStatusLabel('PENDING_REVIEW', 'UNIT_REVIEW', dict)).toBe('待学院初审')
  })
})

describe('enumOptions', () => {
  it('按兜底表生成选项', () => {
    const options = enumOptions('TargetDirection')
    expect(options).toEqual([
      { value: 'HIGHER_BETTER', label: '越大越好' },
      { value: 'LOWER_BETTER', label: '越小越好' },
    ])
  })

  it('字典存在时以字典为准', () => {
    const dict: EnumDict = { RollupMode: { AUTO_SUM: '自动', MANUAL: '手工', EXTRA: '额外' } }
    expect(enumOptions('RollupMode', dict)).toHaveLength(3)
    expect(enumOptions('RollupMode', dict)[2]).toEqual({ value: 'EXTRA', label: '额外' })
  })

  it('未知枚举组返回空数组', () => {
    expect(enumOptions('NoSuchKind')).toEqual([])
  })
})

describe('FALLBACK_ENUM_LABELS', () => {
  it('覆盖表单所需枚举组', () => {
    for (const kind of [
      'PlanSource',
      'MeasureType',
      'StatCaliber',
      'IndicatorNature',
      'TargetDirection',
      'RollupMode',
      'SubjectType',
      'TemplateStatus',
    ]) {
      expect(Object.keys(FALLBACK_ENUM_LABELS[kind] ?? {}).length).toBeGreaterThan(0)
    }
  })
})
