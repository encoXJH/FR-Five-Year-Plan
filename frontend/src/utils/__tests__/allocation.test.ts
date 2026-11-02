import { describe, expect, it } from 'vitest'
import {
  allocationToMatrixRows,
  buildSaveAllocationRequest,
  canAssignNewB,
  computeYearSummary,
  distributeYearTargetsEvenly,
  emptyMatrixTargets,
  isSummableCaliber,
  isSummaryBalanced,
  isPendingAllocationRow,
  percentToDisplay,
  percentToStore,
  rowBooleanPlanYear,
  rowHasTarget,
  rowsMissingOwner,
  rowTotalTarget,
} from '../allocation'
import type { MatrixRow } from '../allocation'
import type { Allocation } from '@/types/allocation'

const YEARS = [2026, 2027, 2028]

function makeRow(partial: Partial<MatrixRow>): MatrixRow {
  return {
    unitId: '1',
    unitCode: 'C01',
    unitName: '智能产业学院',
    ownerId: null,
    isHistorical: false,
    taskStatus: null,
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

describe('isPendingAllocationRow', () => {
  it('仅本地新增和待下发学院可编辑并进入本次下发', () => {
    expect(isPendingAllocationRow({ isHistorical: false, taskStatus: null })).toBe(true)
    expect(isPendingAllocationRow({ isHistorical: false, taskStatus: 'PENDING_DISTRIBUTION' })).toBe(true)
    expect(isPendingAllocationRow({ isHistorical: false, taskStatus: 'DISTRIBUTED' })).toBe(false)
    expect(isPendingAllocationRow({ isHistorical: false, taskStatus: 'IN_PROGRESS' })).toBe(false)
    expect(isPendingAllocationRow({ isHistorical: true, taskStatus: 'PENDING_DISTRIBUTION' })).toBe(false)
  })
})

describe('isSummableCaliber', () => {
  it('仅增量 / 存量做求和校验', () => {
    expect(isSummableCaliber('INCREMENT')).toBe(true)
    expect(isSummableCaliber('STOCK')).toBe(true)
  })

  it('增长率、比率类与是否型不做求和校验', () => {
    expect(isSummableCaliber('GROWTH')).toBe(false)
    expect(isSummableCaliber('RATIO')).toBe(false)
    expect(isSummableCaliber('DIRECT_PERCENT')).toBe(false)
    expect(isSummableCaliber('BOOLEAN')).toBe(false)
  })
})

describe('distributeYearTargetsEvenly', () => {
  it('按年度目标平均分配给可编辑行，余数从首行逐行摊入', () => {
    const rows = [
      makeRow({ unitId: '1' }),
      makeRow({ unitId: '2' }),
      makeRow({ unitId: '3' }),
    ]
    const result = distributeYearTargetsEvenly(rows, [2026], { 2026: 10 }, 1)
    expect(rows.map((row) => row.targets[2026])).toEqual([4, 3, 3])
    expect(result).toEqual({ filledYears: [2026], skippedYears: [] })
  })

  it('目标小于行数时余数只落到前几行', () => {
    const rows = Array.from({ length: 8 }, (_, index) => makeRow({ unitId: String(index + 1) }))
    distributeYearTargetsEvenly(rows, [2030], { 2030: 3 }, 1)
    expect(rows.map((row) => row.targets[2030])).toEqual([1, 1, 1, 0, 0, 0, 0, 0])
  })

  it('先扣除只读行目标，历史与已下发行保持原值', () => {
    const rows = [
      makeRow({ unitId: '1', taskStatus: 'DISTRIBUTED', targets: { 2026: 4 } }),
      makeRow({ unitId: '2' }),
      makeRow({ unitId: '3' }),
    ]
    distributeYearTargetsEvenly(rows, [2026], { 2026: 10 }, 1)
    expect(rows.map((row) => row.targets[2026])).toEqual([4, 3, 3])
  })

  it('年度目标为空或只读行已满足时跳过且不清空原值', () => {
    const rows = [
      makeRow({ unitId: '1', taskStatus: 'DISTRIBUTED', targets: { 2026: 5 } }),
      makeRow({ unitId: '2', targets: { 2026: 1 } }),
    ]
    const result = distributeYearTargetsEvenly(rows, [2026, 2027], { 2026: 5, 2027: null }, 1)
    expect(result).toEqual({ filledYears: [], skippedYears: [2026, 2027] })
    expect(rows[1].targets[2026]).toBe(1)
  })

  it('两位小数刻度分配不产生浮点残差', () => {
    const rows = [
      makeRow({ unitId: '1' }),
      makeRow({ unitId: '2' }),
      makeRow({ unitId: '3' }),
    ]
    const result = distributeYearTargetsEvenly(rows, [2026], { 2026: 10.55 }, 100)
    expect(rows.map((row) => row.targets[2026])).toEqual([3.52, 3.52, 3.51])
    expect(result.filledYears).toEqual([2026])
  })

  it('没有可编辑行时不修改任何数据', () => {
    const rows = [makeRow({ unitId: '1', taskStatus: 'DISTRIBUTED', targets: { 2026: 5 } })]
    const result = distributeYearTargetsEvenly(rows, [2026], { 2026: 10 }, 1)
    expect(rows[0].targets[2026]).toBe(5)
    expect(result).toEqual({ filledYears: [], skippedYears: [2026] })
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

  it('等于指标目标时允许下发', () => {
    const rows = [makeRow({ targets: { 2026: 1, 2027: 2, 2028: 3 } })]
    const summary = computeYearSummary(rows, YEARS, { 2026: 1, 2027: 2, 2028: 3 })
    expect(isSummaryBalanced(summary)).toBe(true)
  })

  it('超过指标目标时允许下发', () => {
    const rows = [makeRow({ targets: { 2026: 2, 2027: 3, 2028: 4 } })]
    const summary = computeYearSummary(rows, YEARS, { 2026: 1, 2027: 2, 2028: 3 })
    expect(summary.every((item) => item.diff !== null && item.diff > 0)).toBe(true)
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

describe('共享矩阵数据转换与保存报文', () => {
  const allocation: Allocation = {
    indicatorId: 'a1',
    version: 7,
    isBalanced: false,
    summary: [],
    rows: [{
      taskId: 't1',
      taskCode: 'A01-C01',
      taskStatus: 'PENDING_DISTRIBUTION',
      unit: { id: 'u1', code: 'C01', name: '智能产业学院' },
      owner: { id: 'owner1', name: '林华' },
      isHistorical: false,
      yearTargets: [
        { year: 2026, target: 2 },
        { year: 2027, target: null },
        { year: 2030, target: 99 },
      ],
    }],
  }

  it('按当前规划期回显目标并保留负责人，忽略规划期外旧目标', () => {
    expect(allocationToMatrixRows(allocation, YEARS, 'INCREMENT')).toEqual([{
      unitId: 'u1',
      unitCode: 'C01',
      unitName: '智能产业学院',
      ownerId: 'owner1',
      isHistorical: false,
      taskStatus: 'PENDING_DISTRIBUTION',
      targets: { 2026: 2, 2027: null, 2028: null },
    }])
  })

  it('是否型仅保留计划年完成标记', () => {
    const booleanAllocation: Allocation = {
      ...allocation,
      rows: [{
        ...allocation.rows[0],
        yearTargets: [{ year: 2026, target: true }, { year: 2027, target: 1 }],
      }],
    }
    const [row] = allocationToMatrixRows(booleanAllocation, YEARS, 'BOOLEAN')
    expect(row.targets).toEqual({ 2026: true, 2027: true, 2028: null })
  })

  it('保存时使用调用方提供的最新版本并完整提交所有年度', () => {
    const rows = allocationToMatrixRows(allocation, YEARS, 'INCREMENT')
    expect(buildSaveAllocationRequest(rows, YEARS, 8)).toEqual({
      version: 8,
      rows: [{
        unitId: 'u1',
        ownerId: 'owner1',
        yearTargets: [
          { year: 2026, target: 2 },
          { year: 2027, target: null },
          { year: 2028, target: null },
        ],
      }],
    })
  })

  it('空目标矩阵覆盖全部年度且各年互不共享引用', () => {
    const targets = emptyMatrixTargets(YEARS)
    expect(targets).toEqual({ 2026: null, 2027: null, 2028: null })
    targets[2026] = 1
    expect(targets[2027]).toBeNull()
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
