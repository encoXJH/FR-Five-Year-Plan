import { describe, expect, it } from 'vitest'
import {
  buildGanttTooltip,
  formatGanttActual,
  formatGanttSegmentLines,
  formatGanttTarget,
  ganttTone,
  milestonesToGanttRows,
  taskToGanttRow,
  trendToGanttRow,
} from '../gantt'
import type { IndicatorTrend } from '@/types/stats'
import type { TaskDetail } from '@/types/task'
import type { Milestone } from '@/types/milestone'

function trend(overrides: Partial<IndicatorTrend> = {}): IndicatorTrend {
  return {
    indicatorId: '1',
    code: 'A01',
    name: '占比指标',
    statCaliber: 'RATIO',
    unitLabel: '%',
    baselineValue: null,
    totalTarget: null,
    cumulativeActual: 0.62,
    cumulativeRate: null,
    years: [{
      year: 2026,
      target: 0.6,
      actualValue: 0.62,
      actualTotal: null,
      actualNumerator: 62,
      actualDenominator: 100,
      completionRate: 1.03,
      achieveStatus: 'ACHIEVED',
    }],
    ...overrides,
  }
}

function task(): TaskDetail {
  return {
    id: 't1',
    planId: 'p1',
    code: 'A01-1-C01',
    indicator: { id: 'ap1', code: 'A01-1', name: '双师型教师占比', statCaliber: 'RATIO', unitLabel: '%' },
    unit: { id: 'u1', code: 'C01', name: '智能产业学院' },
    owner: null,
    status: 'IN_PROGRESS',
    isHistorical: false,
    version: 1,
    years: [{
      year: 2026,
      target: 0.6,
      actualValue: 0.62,
      actualNumerator: 62,
      actualDenominator: 100,
      completionRate: 1.03,
      yearStatus: 'ACHIEVED',
    }],
  }
}

describe('甘特图状态色', () => {
  it('未达标、待补做和滞后均为红色', () => {
    expect(ganttTone('NOT_ACHIEVED')).toBe('danger')
    expect(ganttTone('MAKEUP_PENDING')).toBe('danger')
    expect(ganttTone('LAGGING')).toBe('danger')
  })

  it('达标、进行中、无数据分别使用正常色、主色和灰色', () => {
    expect(ganttTone('ACHIEVED')).toBe('success')
    expect(ganttTone('EXCEEDED')).toBe('success')
    expect(ganttTone('IN_PROGRESS')).toBe('primary')
    expect(ganttTone('NOT_ACTIVATED')).toBe('muted')
    expect(ganttTone(null)).toBe('muted')
  })
})

describe('指标与任务年度分段', () => {
  it('RATIO 使用后端目标 / 实际百分比并保留分子分母，不做平均或求和', () => {
    const row = trendToGanttRow(trend())
    const segment = row.segments[0]
    expect(formatGanttTarget(segment, 'RATIO')).toBe('60.0%')
    expect(formatGanttActual(segment, 'RATIO')).toBe('62.0%')
    expect(segment.actualNumerator).toBe(62)
    expect(segment.actualDenominator).toBe(100)
    expect(buildGanttTooltip(row, segment)).toContain('分子：62 · 分母：100')
  })

  it('B 任务详情直接映射年度状态与分子分母', () => {
    const row = taskToGanttRow(task())
    expect(row.segments[0].status).toBe('ACHIEVED')
    expect(row.segments[0].actualNumerator).toBe(62)
    expect(formatGanttSegmentLines(row, row.segments[0])).toEqual(['目标 60.0%', '实际 62.0%'])
  })

  it('BOOLEAN 仅在计划年显示计划目标，实际值按计划完成状态显示', () => {
    const plan = { year: 2028, target: true, actualValue: 1, status: 'ACHIEVED' }
    const other = { year: 2026, target: null, actualValue: 0, status: 'NOT_STARTED' }
    expect(formatGanttTarget(plan, 'BOOLEAN')).toBe('计划完成')
    expect(formatGanttActual(plan, 'BOOLEAN')).toBe('已完成')
    expect(formatGanttTarget(other, 'BOOLEAN')).toBe('—')
    expect(formatGanttActual(other, 'BOOLEAN')).toBe('未完成')
    expect(ganttTone(other.status)).toBe('muted')
  })
})

describe('里程碑状态条', () => {
  const milestones: Milestone[] = [{
    id: 'm1',
    name: '完成验收',
    relatedYear: 2027,
    dueDate: '2027-06-30',
    completedAt: '2027-06-28T10:00:00+08:00',
    status: 'COMPLETED',
    version: 1,
    createdAt: '2026-01-01T00:00:00+08:00',
  }, {
    id: 'm2',
    name: '未关联年度',
    relatedYear: null,
    dueDate: null,
    status: 'NOT_STARTED',
    version: 1,
    createdAt: '2026-01-01T00:00:00+08:00',
  }]

  it('只按 relatedYear 放置，不虚构其他年度或起止时间', () => {
    const rows = milestonesToGanttRows(milestones, [2026, 2027, 2028])
    expect(rows).toHaveLength(1)
    expect(rows[0].segments).toHaveLength(1)
    expect(rows[0].segments[0].year).toBe(2027)
    expect(rows[0].segments[0].dueDate).toBe('2027-06-30')
  })

  it('tooltip 展示 dueDate、status、completedAt', () => {
    const row = milestonesToGanttRows(milestones, [2027])[0]
    const tooltip = buildGanttTooltip(row, row.segments[0])
    expect(tooltip).toContain('计划完成日期：2027-06-30')
    expect(tooltip).toContain('状态：已完成')
    expect(tooltip).toContain('实际完成时间：2027-06-28 10:00')
  })
})
