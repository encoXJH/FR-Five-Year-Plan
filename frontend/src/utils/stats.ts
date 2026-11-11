import type { StatsOverview, UnitStatItem, WarningItem } from '@/types/stats'
import type { TaskListItem } from '@/types/task'

/** 五年累计使用累计口径，具体年度使用所选年度的达标口径。 */
export function unitStatRate(
  item: Pick<UnitStatItem, 'completionRate' | 'cumulativeRate'>,
  year: number | null,
): number | null {
  return year === null ? item.cumulativeRate : item.completionRate
}

export function sortUnitStatsByYear(items: UnitStatItem[], year: number | null): UnitStatItem[] {
  return [...items].sort((a, b) => (unitStatRate(b, year) ?? -1) - (unitStatRate(a, year) ?? -1))
}

export type WarningTaskScope = Pick<TaskListItem, 'id' | 'planId' | 'displayYear'>

export interface WarningRequest {
  planId: string
  year: number
}

/** 指定年度按计划分组；全部年度按各任务实际展示年度分组。 */
export function buildWarningRequests(tasks: WarningTaskScope[], selectedYear: number | null): WarningRequest[] {
  const requests: WarningRequest[] = []
  const keys = new Set<string>()

  for (const task of tasks) {
    const year = selectedYear ?? task.displayYear
    if (!task.planId || year == null) continue
    const key = `${task.planId}:${year}`
    if (keys.has(key)) continue
    keys.add(key)
    requests.push({ planId: task.planId, year })
  }

  return requests
}

/** 只保留当前可见任务的预警；跨计划重复任务保留落后差值较大的记录。 */
export function mergeVisibleWarnings(
  tasks: Pick<TaskListItem, 'id'>[],
  overviews: Array<Pick<StatsOverview, 'warnings'> | null | undefined>,
): WarningItem[] {
  const visibleTaskIds = new Set(tasks.map((task) => task.id))
  const warningByTask = new Map<string, WarningItem>()

  for (const overview of overviews) {
    for (const warning of overview?.warnings ?? []) {
      if (!visibleTaskIds.has(warning.taskId)) continue
      const previous = warningByTask.get(warning.taskId)
      if (!previous || (warning.lagGap ?? -1) > (previous.lagGap ?? -1)) {
        warningByTask.set(warning.taskId, warning)
      }
    }
  }

  return [...warningByTask.values()].sort((a, b) => {
    const gapDifference = (b.lagGap ?? -1) - (a.lagGap ?? -1)
    if (gapDifference !== 0) return gapDifference
    return a.taskCode.localeCompare(b.taskCode, 'zh-CN') || a.taskId.localeCompare(b.taskId)
  })
}
