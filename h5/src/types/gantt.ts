import type { StatCaliber } from './enums'

export interface GanttYearValue {
  year: number
  target: number | boolean | null
  actualValue: number | null
  actualTotal?: number | null
  actualNumerator?: number | null
  actualDenominator?: number | null
  completionRate: number | null
  status: string | null
  targetVersion?: number | null
  frozenAmount?: number | null
}

export interface GanttNode {
  id: string
  parentId: string | null
  kind: 'indicator' | 'task'
  code: string
  name: string
  caliber: StatCaliber | null
  unitLabel: string | null
  unitName?: string | null
  ownerName?: string | null
  startYear: number | null
  endYear: number | null
  cumulativeRate: number | null
  isHistorical?: boolean
  status?: string | null
  years: GanttYearValue[]
}

export interface GanttMilestone {
  id: string
  indicatorId?: string | null
  taskId?: string | null
  name: string
  relatedYear: number | null
  dueDate: string | null
  status: string
  completedAt?: string | null
  version: number
}

export interface GanttDependency {
  id: string
  predecessorTaskId: string
  successorTaskId: string
  type: 'FS'
  version: number
}

export interface PlanGantt {
  plan: { id: string; name: string; startYear: number; endYear: number; status: string }
  years: number[]
  nodes: GanttNode[]
  milestones: GanttMilestone[]
  dependencies: GanttDependency[]
  criticalPath: { status: string; taskIds: string[]; dependencyIds: string[] }
  graph: { truncated: boolean; omittedDependencies: number; message: string | null }
}
