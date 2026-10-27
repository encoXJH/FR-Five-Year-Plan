import { describe, expect, it } from 'vitest'
import {
  approvedReduction,
  buildMetricValue,
  CALIBER_INPUT_FIELDS,
  computeRatio,
  formatMetricValue,
  isApprovedValueValid,
} from '../submission'
import type { StatCaliber } from '@/types/enums'

const ALL_CALIBERS: StatCaliber[] = [
  'INCREMENT',
  'STOCK',
  'GROWTH',
  'RATIO',
  'DIRECT_PERCENT',
  'BOOLEAN',
]

describe('CALIBER_INPUT_FIELDS 口径→输入项映射（API 3.5）', () => {
  it('六种口径各有对应采集字段', () => {
    expect(CALIBER_INPUT_FIELDS.INCREMENT).toEqual(['amount'])
    expect(CALIBER_INPUT_FIELDS.STOCK).toEqual(['total'])
    expect(CALIBER_INPUT_FIELDS.GROWTH).toEqual(['total'])
    expect(CALIBER_INPUT_FIELDS.RATIO).toEqual(['numerator', 'denominator'])
    expect(CALIBER_INPUT_FIELDS.DIRECT_PERCENT).toEqual(['percent'])
    expect(CALIBER_INPUT_FIELDS.BOOLEAN).toEqual(['achieved'])
    expect(Object.keys(CALIBER_INPUT_FIELDS).sort()).toEqual([...ALL_CALIBERS].sort())
  })
})

describe('buildMetricValue', () => {
  it('只保留本口径字段，其余置 null', () => {
    const value = buildMetricValue('RATIO', {
      numerator: 62,
      denominator: 100,
      amount: 5,
      percent: 0.62,
      achieved: true,
    })
    expect(value).toEqual({
      amount: null,
      total: null,
      numerator: 62,
      denominator: 100,
      percent: null,
      achieved: null,
    })
  })

  it('未填的采集字段置 null 而不是 undefined', () => {
    expect(buildMetricValue('INCREMENT', {}).amount).toBeNull()
    expect(buildMetricValue('BOOLEAN', {}).achieved).toBeNull()
  })
})

describe('computeRatio 占比实时计算', () => {
  it('分子 ÷ 分母', () => {
    expect(computeRatio(62, 100)).toBeCloseTo(0.62)
  })

  it('分母为 0 / 空值返回 null（UI 显示 —）', () => {
    expect(computeRatio(1, 0)).toBeNull()
    expect(computeRatio(1, null)).toBeNull()
    expect(computeRatio(null, 100)).toBeNull()
    expect(computeRatio(undefined, undefined)).toBeNull()
  })
})

describe('isApprovedValueValid 认定值不能大于填报值（60005）', () => {
  it('INCREMENT：认定 ≤ 填报通过，大于不通过', () => {
    expect(isApprovedValueValid('INCREMENT', { amount: 2 }, { amount: 1 })).toBe(true)
    expect(isApprovedValueValid('INCREMENT', { amount: 2 }, { amount: 2 })).toBe(true)
    expect(isApprovedValueValid('INCREMENT', { amount: 2 }, { amount: 3 })).toBe(false)
  })

  it('RATIO：分子 / 分母任一被调大都判不通过', () => {
    const sub = { numerator: 62, denominator: 100 }
    expect(isApprovedValueValid('RATIO', sub, { numerator: 60, denominator: 100 })).toBe(true)
    expect(isApprovedValueValid('RATIO', sub, { numerator: 62, denominator: 120 })).toBe(false)
    expect(isApprovedValueValid('RATIO', sub, { numerator: 63, denominator: 100 })).toBe(false)
  })

  it('BOOLEAN：认定值必须与填报一致', () => {
    expect(isApprovedValueValid('BOOLEAN', { achieved: true }, { achieved: true })).toBe(true)
    expect(isApprovedValueValid('BOOLEAN', { achieved: true }, { achieved: false })).toBe(false)
  })

  it('填报值缺失时认定值不能出现', () => {
    expect(isApprovedValueValid('STOCK', { total: null }, { total: 5 })).toBe(false)
  })
})

describe('approvedReduction 核减量', () => {
  it('返回第一个被核减字段的差值', () => {
    expect(approvedReduction('INCREMENT', { amount: 2 }, { amount: 1 })).toBe(1)
    expect(approvedReduction('RATIO', { numerator: 62, denominator: 100 }, { numerator: 62, denominator: 90 })).toBe(10)
  })

  it('无核减返回 null', () => {
    expect(approvedReduction('INCREMENT', { amount: 2 }, { amount: 2 })).toBeNull()
    expect(approvedReduction('BOOLEAN', { achieved: true }, { achieved: true })).toBeNull()
  })
})

describe('formatMetricValue 按口径展示（UI 规范 3.4）', () => {
  it('INCREMENT 带 + 前缀和单位', () => {
    expect(formatMetricValue({ amount: 1 }, 'INCREMENT', '项')).toBe('+1 项')
    expect(formatMetricValue({ amount: 0 }, 'INCREMENT', '项')).toBe('0 项')
  })

  it('RATIO 显示 分子 / 分母（占比 %）', () => {
    expect(formatMetricValue({ numerator: 62, denominator: 100 }, 'RATIO')).toBe('62 / 100（62.0%）')
    expect(formatMetricValue({ numerator: 1, denominator: 0 }, 'RATIO')).toBe('1 / 0')
  })

  it('DIRECT_PERCENT 小数 ×100 显示 %', () => {
    expect(formatMetricValue({ percent: 0.62 }, 'DIRECT_PERCENT')).toBe('62.0%')
  })

  it('BOOLEAN 显示 是 / 否', () => {
    expect(formatMetricValue({ achieved: true }, 'BOOLEAN')).toBe('是')
    expect(formatMetricValue({ achieved: false }, 'BOOLEAN')).toBe('否')
  })

  it('空值显示 —', () => {
    expect(formatMetricValue(null, 'INCREMENT')).toBe('—')
    expect(formatMetricValue({ amount: null }, 'INCREMENT')).toBe('—')
  })
})
