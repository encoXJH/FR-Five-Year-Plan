import { describe, expect, it } from 'vitest'
import { normalizeStatsTree } from '../stats'
import type { StatsTreeNode } from '@/types/stats'

function node(
  id: string,
  level: StatsTreeNode['level'],
  children: StatsTreeNode[] = [],
): StatsTreeNode {
  return {
    id,
    code: id.toUpperCase(),
    name: id,
    level,
    target: null,
    actualValue: null,
    completionRate: null,
    achieveStatus: null,
    isHistorical: level === 'A_PLUS',
    children,
  }
}

describe('normalizeStatsTree', () => {
  it('A→B 新数据保持不变', () => {
    const direct = node('b-direct', 'B')
    const result = normalizeStatsTree([node('a', 'A', [direct])])
    expect(result).toHaveLength(1)
    expect(result[0].children?.map((item) => item.id)).toEqual(['b-direct'])
    expect(result[0].children?.[0]).toMatchObject({ code: direct.code, name: direct.name })
  })

  it('历史中间层不展示，其 B 与直承 B 合并且不重复', () => {
    const tree = [
      node('a', 'A', [
        node('b-direct', 'B'),
        node('legacy-1', 'A_PLUS', [node('b-old-1', 'B')]),
        node('legacy-2', 'A_PLUS', [node('b-old-2', 'B')]),
      ]),
    ]
    const result = normalizeStatsTree(tree)
    expect(result).toHaveLength(1)
    expect(result[0].level).toBe('A')
    expect(result[0].children?.map((item) => item.id)).toEqual([
      'b-direct',
      'b-old-1',
      'b-old-2',
    ])
    expect(JSON.stringify(result)).not.toContain('A_PLUS')
  })
})
