import { describe, expect, it } from 'vitest'
import { sortUnitStatsByYear, unitStatRate } from '../stats'
import type { UnitStatItem } from '@/types/stats'

function unit(id: string, completionRate: number | null, cumulativeRate: number | null): UnitStatItem {
  return {
    unit: { id, code: id, name: id },
    taskCount: 1,
    achievedCount: 0,
    completionRate,
    cumulativeRate,
    rank: 0,
  }
}

describe('执行单元排名统计口径', () => {
  const first = unit('A', 0.2, 0.8)
  const second = unit('B', 0.7, 0.3)

  it('五年累计按累计完成率排序', () => {
    expect(sortUnitStatsByYear([second, first], null).map((item) => item.unit?.id)).toEqual(['A', 'B'])
    expect(unitStatRate(first, null)).toBe(0.8)
  })

  it('具体年度切换为当年完成率并重新排序', () => {
    expect(sortUnitStatsByYear([first, second], 2026).map((item) => item.unit?.id)).toEqual(['B', 'A'])
    expect(unitStatRate(first, 2026)).toBe(0.2)
  })

  it('空值排在最后且不修改接口原数组', () => {
    const missing = unit('C', null, null)
    const rows = [missing, first]

    expect(sortUnitStatsByYear(rows, 2026).map((item) => item.unit?.id)).toEqual(['A', 'C'])
    expect(rows.map((item) => item.unit?.id)).toEqual(['C', 'A'])
  })
})
