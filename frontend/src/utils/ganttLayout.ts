import type { GanttDependency, GanttNode } from '@/types/gantt'

export const GANTT_ROW_HEIGHT = 64
export const GANTT_OVERSCAN = 6

export interface GanttWindow {
  start: number
  end: number
  offsetTop: number
}

export function visibleGanttWindow(scrollTop: number, viewportHeight: number, total: number): GanttWindow {
  const start = Math.max(0, Math.floor(scrollTop / GANTT_ROW_HEIGHT) - GANTT_OVERSCAN)
  const count = Math.ceil(viewportHeight / GANTT_ROW_HEIGHT) + GANTT_OVERSCAN * 2
  return { start, end: Math.min(total, start + count), offsetTop: start * GANTT_ROW_HEIGHT }
}

export function flattenGanttNodes(nodes: GanttNode[], collapsed: Set<string>): GanttNode[] {
  const indicators = nodes.filter((node) => node.kind === 'indicator')
  const tasksByParent = new Map<string, GanttNode[]>()
  for (const node of nodes) {
    if (node.kind !== 'task' || !node.parentId) continue
    const list = tasksByParent.get(node.parentId) ?? []
    list.push(node)
    tasksByParent.set(node.parentId, list)
  }
  return indicators.flatMap((indicator) => [
    indicator,
    ...(collapsed.has(indicator.id) ? [] : (tasksByParent.get(indicator.id) ?? [])),
  ])
}

export function yearStart(year: number): number {
  return Date.UTC(year, 0, 1)
}

export function yearEnd(year: number): number {
  return Date.UTC(year + 1, 0, 1)
}

export function snapTimestampToYear(timestamp: number, years: number[]): number | null {
  let result: number | null = null
  let distance = Number.POSITIVE_INFINITY
  for (const year of years) {
    const center = (yearStart(year) + yearEnd(year)) / 2
    const next = Math.abs(timestamp - center)
    if (next < distance) {
      distance = next
      result = year
    }
  }
  return result
}

export interface DependencyLayout {
  id: string
  predecessorTaskId: string
  successorTaskId: string
  fromRow: number
  toRow: number
  critical: boolean
}

export function layoutDependencies(
  dependencies: GanttDependency[],
  visibleRows: GanttNode[],
  globalStart: number,
  criticalIds: Set<string>,
): DependencyLayout[] {
  const rows = new Map(visibleRows.map((node, index) => [node.id, globalStart + index]))
  return dependencies.flatMap((dependency) => {
    const fromRow = rows.get(dependency.predecessorTaskId)
    const toRow = rows.get(dependency.successorTaskId)
    if (fromRow === undefined || toRow === undefined) return []
    return [{
      id: dependency.id,
      predecessorTaskId: dependency.predecessorTaskId,
      successorTaskId: dependency.successorTaskId,
      fromRow,
      toRow,
      critical: criticalIds.has(dependency.id),
    }]
  })
}
