import { describe, expect, it } from 'vitest'
import { buildWarningRequests, mergeVisibleWarnings, sortUnitStatsByYear, unitStatRate } from '../stats'
import type { UnitStatItem, WarningItem } from '@/types/stats'
import type { WarningTaskScope } from '../stats'

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

function warningTask(id: string, planId = 'plan-1', displayYear: number | null = 2026): WarningTaskScope {
  return { id, planId, displayYear }
}

function warning(taskId: string, lagGap: number | null, year = 2026): WarningItem {
  return {
    taskId,
    taskCode: `${taskId}-code`,
    indicatorName: `${taskId}-指标`,
    unit: { id: 'unit-1', code: 'C01', name: '智能产业学院' },
    year,
    completionRate: 0.1,
    timeProgress: 0.8,
    lagGap,
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

describe('工作台滞后预警统计口径', () => {
  it('指定年度时按计划去重并统一请求所选年度', () => {
    const tasks = [warningTask('1'), warningTask('2', 'plan-1', 2027), warningTask('3', 'plan-2')]

    expect(buildWarningRequests(tasks, 2028)).toEqual([
      { planId: 'plan-1', year: 2028 },
      { planId: 'plan-2', year: 2028 },
    ])
  })

  it('全部年度时按计划和任务展示年度分组，忽略空年度', () => {
    const tasks = [
      warningTask('1'),
      warningTask('2'),
      warningTask('3', 'plan-1', 2027),
      warningTask('4', 'plan-2', 2026),
      warningTask('5', 'plan-2', null),
      warningTask('6', 'plan-3', null),
    ]

    expect(buildWarningRequests(tasks, null)).toEqual([
      { planId: 'plan-1', year: 2026 },
      { planId: 'plan-1', year: 2027 },
      { planId: 'plan-2', year: 2026 },
    ])
    expect(buildWarningRequests([], 2026)).toEqual([])
    expect(buildWarningRequests([warningTask('7', 'plan-1', null)], null)).toEqual([])
  })

  it('仅保留当前可见任务，不使用全校预警总数推算', () => {
    const allSchoolOverview = { warningCount: 99, warnings: [warning('visible', 0.6), warning('other-unit', 0.9)] }
    const emptyOverview = { warningCount: 99, warnings: [] }

    expect(mergeVisibleWarnings([warningTask('visible')], [allSchoolOverview]).map((item) => item.taskId)).toEqual(['visible'])
    expect(mergeVisibleWarnings([warningTask('visible')], [emptyOverview])).toEqual([])
  })

  it('跨计划按任务去重，保留较大落后差值并降序排列', () => {
    const result = mergeVisibleWarnings(
      [warningTask('1'), warningTask('2'), warningTask('3')],
      [
        { warnings: [warning('2', null), warning('1', 0.6)] },
        { warnings: [warning('3', 0.7), warning('1', 0.8)] },
      ],
    )

    expect(result.map((item) => item.taskId)).toEqual(['1', '3', '2'])
    expect(result[0]?.lagGap).toBe(0.8)
    expect(result.filter((item) => item.taskId === '1')).toHaveLength(1)
  })
})
