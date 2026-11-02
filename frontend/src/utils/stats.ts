import type { SubjectType } from '@/types/enums'
import type { IndicatorTreeNode } from '@/types/indicator'
import type { StatsTreeNode } from '@/types/stats'

export interface SubjectDimensionRow {
  key: SubjectType
  taskCount: number
  achievedCount: number
  completionRate: number | null
  cumulativeRate: number | null
}

interface MutableSubjectRow extends SubjectDimensionRow {
  cumulativeRates: number[]
}

/** 按 A 的主体归属汇总其全部 B；统计树不含主体字段，因此复用指标树建立映射。 */
export function aggregateSubjectDimension(
  tree: StatsTreeNode[],
  indicators: Array<Pick<IndicatorTreeNode, 'id' | 'subjectType'>>,
): SubjectDimensionRow[] {
  const subjectByIndicator = new Map(indicators.map((item) => [item.id, item.subjectType]))
  const grouped = new Map<SubjectType, MutableSubjectRow>()

  for (const root of tree) {
    const subjectType = subjectByIndicator.get(root.id)
    if (!subjectType) continue
    const walk = (nodes: StatsTreeNode[]): void => {
      for (const node of nodes) {
        if (node.level === 'B') {
          const entry = grouped.get(subjectType) ?? {
            key: subjectType,
            taskCount: 0,
            achievedCount: 0,
            completionRate: null,
            cumulativeRate: null,
            cumulativeRates: [],
          }
          entry.taskCount += 1
          if (node.isAchieved === true || node.achieveStatus === 'ACHIEVED' || node.achieveStatus === 'EXCEEDED') {
            entry.achievedCount += 1
          }
          if (typeof node.cumulativeRate === 'number' && Number.isFinite(node.cumulativeRate)) {
            entry.cumulativeRates.push(node.cumulativeRate)
          }
          grouped.set(subjectType, entry)
        }
        if (node.children?.length) walk(node.children)
      }
    }
    walk(root.children ?? [])
  }

  return (['SCHOOL', 'MAJOR_GROUP'] as const).flatMap((key) => {
    const entry = grouped.get(key)
    if (!entry) return []
    return [{
      key,
      taskCount: entry.taskCount,
      achievedCount: entry.achievedCount,
      completionRate: entry.taskCount > 0 ? entry.achievedCount / entry.taskCount : null,
      cumulativeRate: entry.cumulativeRates.length > 0
        ? entry.cumulativeRates.reduce((sum, rate) => sum + rate, 0) / entry.cumulativeRates.length
        : null,
    }]
  })
}
