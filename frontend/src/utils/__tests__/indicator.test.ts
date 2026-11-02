import { describe, expect, it } from 'vitest'
import {
  booleanPlanYear,
  CALIBER_BY_MEASURE,
  formatTargetValue,
  groupIndicatorsByDirection,
  hasQuantitativeFields,
  indexStatsTree,
  isPercentCaliber,
  isValidCaliberCombo,
  parseCollapsedDirectionKeys,
  parseExpandedIndicatorIds,
  serializeCollapsedDirectionKeys,
  serializeExpandedIndicatorIds,
  sumYearTargets,
  yearTargetMap,
} from '../indicator'
import { EMPTY_DISPLAY } from '../format'
import type { MeasureType, StatCaliber } from '@/types/enums'
import type { StatsTreeNode } from '@/types/stats'

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

describe('建设方向分组', () => {
  it('按 directionId + directionName 分组，按 sort 排序且未分配方向置尾', () => {
    const groups = groupIndicatorsByDirection([
      { id: 'b-late', directionId: '2', directionName: '第二方向', sort: 20 },
      { id: 'a-first', directionId: '1', directionName: '落实立德树人根本任务', sort: 10 },
      { id: 'unassigned', directionId: null, directionName: null, sort: 1 },
      { id: 'b-early', directionId: '2', directionName: '第二方向', sort: 15 },
    ])
    expect(groups.map((group) => group.directionName)).toEqual([
      '落实立德树人根本任务',
      '第二方向',
      '未分配建设方向',
    ])
    expect(groups[1].indicators.map((item) => item.id)).toEqual(['b-early', 'b-late'])
  })

  it('sort 相同时保持首次出现顺序，同名不同 ID 或同 ID 不同名不混组', () => {
    const groups = groupIndicatorsByDirection([
      { id: '1', directionId: '2', directionName: '同名', sort: 10 },
      { id: '2', directionId: '1', directionName: '同名', sort: 10 },
      { id: '3', directionId: '2', directionName: '不同名', sort: 10 },
    ])
    expect(groups.map((group) => [group.directionId, group.directionName])).toEqual([
      ['2', '同名'],
      ['1', '同名'],
      ['2', '不同名'],
    ])
    expect(groupIndicatorsByDirection([])).toEqual([])
  })

  it('方向折叠状态兼容损坏数据并去重持久化', () => {
    expect(parseCollapsedDirectionKeys('["[\\"1\\",\\"方向\\"]","[\\"1\\",\\"方向\\"]","",2]'))
      .toEqual(['["1","方向"]'])
    expect(parseCollapsedDirectionKeys('{broken')).toEqual([])
    expect(parseCollapsedDirectionKeys(null)).toEqual([])
    expect(serializeCollapsedDirectionKeys(['["1","方向"]', '["1","方向"]']))
      .toBe('["[\\"1\\",\\"方向\\"]"]')
  })
})

describe('统计树进度索引', () => {
  it('A 按指标 ID、B 按 taskId 合并当前值、完成率、累计完成率和年度状态', () => {
    const tree: StatsTreeNode[] = [{
      id: 'a1',
      code: 'A01',
      name: '指标',
      level: 'A',
      target: 10,
      actualValue: 4,
      completionRate: 0.4,
      achieveStatus: 'IN_PROGRESS',
      cumulativeActual: 6,
      cumulativeRate: 0.3,
      isHistorical: false,
      children: [{
        id: 'task-year-1',
        code: 'A01-C01',
        name: '指标',
        level: 'B',
        target: 2,
        actualValue: 1,
        completionRate: 0.5,
        achieveStatus: null,
        cumulativeActual: 3,
        cumulativeRate: 0.25,
        taskId: 'task-1',
        yearStatus: 'ACHIEVED',
        isHistorical: false,
      }],
    }]
    const index = indexStatsTree(tree)
    expect(index.indicators.get('a1')).toMatchObject({ actualValue: 4, completionRate: 0.4, cumulativeRate: 0.3 })
    expect(index.tasks.get('task-1')).toMatchObject({
      actualValue: 1,
      completionRate: 0.5,
      cumulativeRate: 0.25,
      yearStatus: 'ACHIEVED',
    })
    expect(index.tasks.has('task-year-1')).toBe(false)
  })
})

describe('指标展开状态', () => {
  it('本地状态仅恢复合法雪花 ID，并兼容损坏数据', () => {
    expect(parseExpandedIndicatorIds('["1001","1001","1002","bad",1003,null]')).toEqual(['1001', '1002'])
    expect(parseExpandedIndicatorIds('{broken')).toEqual([])
    expect(parseExpandedIndicatorIds('{}')).toEqual([])
    expect(parseExpandedIndicatorIds(null)).toEqual([])
  })

  it('序列化时去重并保持展开顺序', () => {
    expect(serializeExpandedIndicatorIds(['1002', '1001', '1002'])).toBe('["1002","1001"]')
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
