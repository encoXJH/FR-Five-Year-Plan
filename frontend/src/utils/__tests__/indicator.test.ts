import { describe, expect, it } from 'vitest'
import {
  booleanPlanYear,
  CALIBER_BY_MEASURE,
  formatTargetValue,
  hasQuantitativeFields,
  isPercentCaliber,
  isValidCaliberCombo,
  sumYearTargets,
  yearTargetMap,
} from '../indicator'
import { EMPTY_DISPLAY } from '../format'
import type { MeasureType, StatCaliber } from '@/types/enums'

describe('CALIBER_BY_MEASURE（API 第 9 章合法组合）', () => {
  it('每种计量类型的口径组合与约定一致', () => {
    expect(CALIBER_BY_MEASURE.NUMBER).toEqual(['INCREMENT', 'STOCK'])
    expect(CALIBER_BY_MEASURE.BOOLEAN).toEqual(['BOOLEAN'])
    expect(CALIBER_BY_MEASURE.PERCENT_QUANTIFIABLE).toEqual(['GROWTH', 'RATIO'])
    expect(CALIBER_BY_MEASURE.PERCENT_DIRECT).toEqual(['DIRECT_PERCENT'])
  })

  it('isValidCaliberCombo 判定合法与非法', () => {
    expect(isValidCaliberCombo('NUMBER', 'INCREMENT')).toBe(true)
    expect(isValidCaliberCombo('NUMBER', 'RATIO')).toBe(false)
    expect(isValidCaliberCombo('PERCENT_QUANTIFIABLE', 'DIRECT_PERCENT')).toBe(false)
  })
})

describe('hasQuantitativeFields', () => {
  it('非量化 A 只维护基础信息，量化 A 与 A+ 维护量化字段', () => {
    expect(hasQuantitativeFields('A', false)).toBe(false)
    expect(hasQuantitativeFields('A', true)).toBe(true)
    expect(hasQuantitativeFields('A_PLUS', false)).toBe(true)
  })
})

describe('isPercentCaliber', () => {
  it('占比 / 直填百分比为比率类', () => {
    expect(isPercentCaliber('RATIO')).toBe(true)
    expect(isPercentCaliber('DIRECT_PERCENT')).toBe(true)
    expect(isPercentCaliber('INCREMENT')).toBe(false)
  })
})

describe('formatTargetValue', () => {
  it('比率类按小数 ×100 显示', () => {
    expect(formatTargetValue(0.62, 'RATIO')).toBe('62.0%')
    expect(formatTargetValue(1.069, 'DIRECT_PERCENT')).toBe('106.9%')
  })
  it('数值类千分位显示', () => {
    expect(formatTargetValue(25000, 'INCREMENT')).toBe('25,000')
    expect(formatTargetValue(0, 'STOCK')).toBe('0')
  })
  it('是否型：true 显示是，false 显示 —', () => {
    expect(formatTargetValue(true, 'BOOLEAN')).toBe('是')
    expect(formatTargetValue(false, 'BOOLEAN')).toBe(EMPTY_DISPLAY)
  })
  it('空值显示 —', () => {
    expect(formatTargetValue(null, 'INCREMENT')).toBe(EMPTY_DISPLAY)
    expect(formatTargetValue(undefined, 'GROWTH')).toBe(EMPTY_DISPLAY)
  })
})

describe('yearTargetMap / booleanPlanYear / sumYearTargets', () => {
  const targets = [
    { year: 2026, target: 1 },
    { year: 2027, target: 2 },
    { year: 2028, target: null },
  ]

  it('yearTargetMap 按年份索引', () => {
    const map = yearTargetMap(targets)
    expect(map.get(2026)).toBe(1)
    expect(map.get(2028)).toBeNull()
    expect(map.get(2029)).toBeUndefined()
  })

  it('booleanPlanYear 取 target 为 true 的年份', () => {
    expect(
      booleanPlanYear([
        { year: 2026, target: null },
        { year: 2028, target: true },
      ]),
    ).toBe(2028)
    expect(booleanPlanYear([{ year: 2026, target: null }])).toBeNull()
  })

  it('sumYearTargets 只累计数值目标', () => {
    expect(sumYearTargets(targets)).toBe(3)
    expect(sumYearTargets(null)).toBe(0)
  })
})

describe('类型约束', () => {
  it('CALIBER_BY_MEASURE 覆盖全部计量类型', () => {
    const measureTypes: MeasureType[] = ['NUMBER', 'BOOLEAN', 'PERCENT_QUANTIFIABLE', 'PERCENT_DIRECT']
    for (const type of measureTypes) {
      expect(CALIBER_BY_MEASURE[type].length).toBeGreaterThan(0)
    }
    const calibers: StatCaliber[] = ['INCREMENT', 'STOCK', 'GROWTH', 'RATIO', 'DIRECT_PERCENT', 'BOOLEAN']
    const covered = Object.values(CALIBER_BY_MEASURE).flat()
    for (const caliber of calibers) {
      expect(covered).toContain(caliber)
    }
  })
})
