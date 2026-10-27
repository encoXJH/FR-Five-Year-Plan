import { describe, expect, it } from 'vitest'
import {
  canAssignNewB,
  computeYearSummary,
  isSummableCaliber,
  isSummaryBalanced,
  percentToDisplay,
  percentToStore,
  rowBooleanPlanYear,
  rowHasTarget,
  rowsMissingOwner,
  rowTotalTarget,
} from '../allocation'
import type { MatrixRow } from '../allocation'

const YEARS = [2026, 2027, 2028]

function makeRow(partial: Partial<MatrixRow>): MatrixRow {
  return {
    unitId: '1',
    unitCode: 'C01',
    unitName: '智能产业学院',
    ownerId: null,
    isHistorical: false,
    targets: {},
    ...partial,
  }
}

describe('canAssignNewB', () => {
  it('新增或接收候选必须同时启用且具备 B 承接资格', () => {
    expect(canAssignNewB({ isEnabled: true, isBAssignable: true })).toBe(true)
    expect(canAssignNewB({ isEnabled: false, isBAssignable: true })).toBe(false)
    expect(canAssignNewB({ isEnabled: true, isBAssignable: false })).toBe(false)
    expect(canAssignNewB({ isEnabled: true })).toBe(false)
  })
})

describe('isSummableCaliber', () => {
  it('增量 / 存量 / 增长率求和校验', () => {
    expect(isSummableCaliber('INCREMENT')).toBe(true)
    expect(isSummableCaliber('STOCK')).toBe(true)
    expect(isSummableCaliber('GROWTH')).toBe(true)
  })

  it('比率类与是否型不做求和校验', () => {
    expect(isSummableCaliber('RATIO')).toBe(false)
    expect(isSummableCaliber('DIRECT_PERCENT')).toBe(false)
    expect(isSummableCaliber('BOOLEAN')).toBe(false)
  })
})

describe('rowHasTarget / rowTotalTarget / rowBooleanPlanYear', () => {
  it('全 0 / 全 null 视为无目标', () => {
    expect(rowHasTarget({ 2026: 0, 2027: null })).toBe(false)
    expect(rowHasTarget({ 2026: 1 })).toBe(true)
    expect(rowHasTarget({ 2028: true })).toBe(true)
  })

  it('五年合计只加数值目标', () => {
    expect(rowTotalTarget({ 2026: 2, 2027: null, 2028: true })).toBe(2)
  })

  it('是否型计划完成年', () => {
    expect(rowBooleanPlanYear({ 2026: null, 2028: true })).toBe(2028)
    expect(rowBooleanPlanYear({ 2026: null })).toBeNull()
  })
})

describe('computeYearSummary / isSummaryBalanced', () => {
  it('逐年计算合计与差值', () => {
    const rows = [
      makeRow({ targets: { 2026: 2, 2027: 1, 2028: 0 } }),
      makeRow({ unitId: '2', targets: { 2026: 1, 2027: 1, 2028: 3 } }),
    ]
    const summary = computeYearSummary(rows, YEARS, { 2026: 3, 2027: 3, 2028: 3 })
    expect(summary).toEqual([
      { year: 2026, indicatorTarget: 3, allocatedTarget: 3, diff: 0 },
      { year: 2027, indicatorTarget: 3, allocatedTarget: 2, diff: -1 },
      { year: 2028, indicatorTarget: 3, allocatedTarget: 3, diff: 0 },
    ])
    expect(isSummaryBalanced(summary)).toBe(false)
  })

  it('指标目标为空时 diff 为 null 且视为平衡', () => {
    const summary = computeYearSummary([], YEARS, { 2026: null, 2027: null, 2028: null })
    expect(summary.every((item) => item.diff === null)).toBe(true)
    expect(isSummaryBalanced(summary)).toBe(true)
  })

  it('全部平衡', () => {
    const rows = [makeRow({ targets: { 2026: 1, 2027: 2, 2028: 3 } })]
    const summary = computeYearSummary(rows, YEARS, { 2026: 1, 2027: 2, 2028: 3 })
    expect(isSummaryBalanced(summary)).toBe(true)
  })
})

describe('rowsMissingOwner', () => {
  it('只统计非历史且有目标但未指定负责人的行', () => {
    const rows = [
      makeRow({ targets: { 2026: 1 } }),
      makeRow({ unitId: '2', targets: { 2026: 0 } }),
      makeRow({ unitId: '3', ownerId: 'u1', targets: { 2026: 2 } }),
      makeRow({ unitId: '4', targets: { 2028: true } }),
      makeRow({ unitId: '5', isHistorical: true, targets: { 2026: 3 } }),
    ]
    expect(rowsMissingOwner(rows).map((row) => row.unitId)).toEqual(['1', '4'])
  })
})

describe('percentToDisplay / percentToStore', () => {
  it('小数 ↔ 百分比显示互转', () => {
    expect(percentToDisplay(0.625)).toBe(62.5)
    expect(percentToStore(62.5)).toBe(0.625)
    expect(percentToDisplay(null)).toBeUndefined()
    expect(percentToStore(undefined)).toBeNull()
  })
})
