import { describe, expect, it } from 'vitest'
import {
  GANTT_ROW_HEIGHT,
  flattenGanttNodes,
  layoutDependencies,
  snapTimestampToYear,
  visibleGanttWindow,
  yearEnd,
  yearStart,
} from '../ganttLayout'
import type { GanttNode } from '@/types/gantt'

function node(id: string, kind: 'indicator' | 'task', parentId: string | null = null): GanttNode {
  return { id, kind, parentId, code: id, name: id, caliber: 'INCREMENT', unitLabel: '人', startYear: 2026, endYear: 2027, cumulativeRate: 0, years: [] }
}

describe('甘特图虚拟窗口', () => {
  it('首尾均保留缓冲且不越界', () => {
    expect(visibleGanttWindow(0, 320, 1000)).toEqual({ start: 0, end: 17, offsetTop: 0 })
    const end = visibleGanttWindow(1000 * GANTT_ROW_HEIGHT, 320, 1000)
    expect(end.end).toBe(1000)
    expect(end.offsetTop).toBe(end.start * GANTT_ROW_HEIGHT)
  })

  it('千节点只保留可视行', () => {
    const window = visibleGanttWindow(6400, 320, 1000)
    expect(window.end - window.start).toBeLessThan(30)
  })
})

describe('甘特图节点与依赖布局', () => {
  it('折叠A时隐藏其全部B', () => {
    const rows = [node('a1', 'indicator'), node('b1', 'task', 'a1'), node('b2', 'task', 'a1')]
    expect(flattenGanttNodes(rows, new Set()).map((item) => item.id)).toEqual(['a1', 'b1', 'b2'])
    expect(flattenGanttNodes(rows, new Set(['a1'])).map((item) => item.id)).toEqual(['a1'])
  })

  it('只绘制可视依赖并保留关键标记', () => {
    const rows = [node('b1', 'task'), node('b2', 'task')]
    const result = layoutDependencies([{ id: 'd1', predecessorTaskId: 'b1', successorTaskId: 'b2', type: 'FS', version: 1 }], rows, 10, new Set(['d1']))
    expect(result).toEqual([{ id: 'd1', predecessorTaskId: 'b1', successorTaskId: 'b2', fromRow: 10, toRow: 11, critical: true }])
  })
})

describe('甘特图时间换算', () => {
  it('使用UTC避免浏览器时区差异', () => {
    expect(yearStart(2028)).toBe(Date.UTC(2028, 0, 1))
    expect(yearEnd(2028)).toBe(Date.UTC(2029, 0, 1))
  })

  it('拖拽吸附到最近规划年度', () => {
    expect(snapTimestampToYear(Date.UTC(2027, 5, 1), [2026, 2027, 2028])).toBe(2027)
    expect(snapTimestampToYear(0, [])).toBeNull()
  })
})
