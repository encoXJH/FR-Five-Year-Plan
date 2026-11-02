import { describe, expect, it } from 'vitest'
import { aggregateSubjectDimension } from '../stats'
import type { StatsTreeNode } from '@/types/stats'

function task(id: string, achieved: boolean, cumulativeRate: number | null): StatsTreeNode {
  return {
    id,
    code: id,
    name: id,
    level: 'B',
    target: 1,
    actualValue: achieved ? 1 : 0,
    completionRate: achieved ? 1 : 0,
    achieveStatus: null,
    isAchieved: achieved,
    cumulativeRate,
    isHistorical: false,
    children: [],
  }
}

function indicator(id: string, children: StatsTreeNode[]): StatsTreeNode {
  return {
    id,
    code: id,
    name: id,
    level: 'A',
    target: 1,
    actualValue: null,
    completionRate: null,
    achieveStatus: null,
    isHistorical: false,
    children,
  }
}

describe('aggregateSubjectDimension 主体统计', () => {
  it('按 SCHOOL / MAJOR_GROUP 汇总各自全部 B，保持 PRD 展示顺序', () => {
    const rows = aggregateSubjectDimension(
      [
        indicator('a-major', [task('b3', false, null)]),
        indicator('a-school', [task('b1', true, 1), task('b2', false, 0.5)]),
      ],
      [
        { id: 'a-school', subjectType: 'SCHOOL' },
        { id: 'a-major', subjectType: 'MAJOR_GROUP' },
      ],
    )
    expect(rows.map((row) => row.key)).toEqual(['SCHOOL', 'MAJOR_GROUP'])
    expect(rows[0]).toEqual({
      key: 'SCHOOL',
      taskCount: 2,
      achievedCount: 1,
      completionRate: 0.5,
      cumulativeRate: 0.75,
    })
    expect(rows[1]).toEqual({
      key: 'MAJOR_GROUP',
      taskCount: 1,
      achievedCount: 0,
      completionRate: 0,
      cumulativeRate: null,
    })
  })

  it('无任务或缺少主体映射时返回空数组供页面展示友好空态', () => {
    expect(aggregateSubjectDimension([indicator('a1', [])], [{ id: 'a1', subjectType: 'SCHOOL' }])).toEqual([])
    expect(aggregateSubjectDimension([indicator('a1', [task('b1', true, 1)])], [])).toEqual([])
  })
})
