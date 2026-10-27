import type { UnitRef, UserRef } from './api'
import type { StatCaliber, TaskStatus, TaskYearStatus } from './enums'

/** 任务关联的指标引用 */
export interface TaskIndicatorRef {
  id: string
  code: string
  name: string
  statCaliber?: StatCaliber | null
  unitLabel?: string | null
  /** 佐证材料要求（填报页展示） */
  evidenceRequirement?: string | null
}

/** 任务所属计划引用 */
export interface TaskPlanRef {
  id: string
  name: string
  startYear: number
  endYear: number
}

/** GET /tasks 列表项；年度字段取查询 year，未指定时取计划当前激活年度 */
export interface TaskListItem {
  id: string
  planId: string
  /** 任务编号，如 A01-2-C03 */
  code: string
  indicator: TaskIndicatorRef | null
  topIndicator?: TaskIndicatorRef | null
  unit: UnitRef | null
  owner: UserRef | null
  status: TaskStatus
  /** 五年目标（BOOLEAN 为 boolean；比率类为小数） */
  totalTarget?: number | boolean | null
  /** 展示年度的目标 / 完成值（年度见 yearStatus 对应行；未激活年度为 null） */
  yearTarget?: number | boolean | null
  actualValue?: number | null
  /** 完成率（小数），null 显示 — */
  completionRate?: number | null
  /** 五年累计完成值（小数；INCREMENT 各年之和，STOCK/GROWTH 最新总量，比率类最新值） */
  cumulativeActual?: number | null
  /** 五年累计完成率（小数）= 累计完成 ÷ 五年总量目标 */
  cumulativeRate?: number | null
  yearStatus?: TaskYearStatus | null
  distributedAt?: string | null
  acceptedAt?: string | null
  /** 历史任务保留只读详情，不参与任何执行写操作 */
  isHistorical: boolean
  /** 展示年度填报截止日（YYYY-MM-DD；无展示年度时为 null） */
  deadline?: string | null
  version: number
}

/** task_year 年度明细（GET /tasks/{taskId}） */
export interface TaskYearItem {
  year: number
  target: number | boolean | null
  originalTarget?: number | null
  actualValue: number | null
  /** 仅 RATIO 口径有值 */
  actualNumerator?: number | null
  actualDenominator?: number | null
  completionRate: number | null
  isAchieved?: boolean | null
  yearStatus: TaskYearStatus
  version?: number
}

/** GET /tasks/{taskId} 详情 */
export interface TaskDetail extends TaskListItem {
  years: TaskYearItem[]
  plan?: TaskPlanRef | null
}

/** GET /tasks 查询参数 */
export interface TaskQuery {
  unitId?: string
  indicatorId?: string
  year?: number
  status?: TaskStatus
  yearStatus?: TaskYearStatus
  isHistorical?: boolean
  page?: number
  pageSize?: number
}
