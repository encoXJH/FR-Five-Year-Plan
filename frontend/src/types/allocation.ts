import type { UnitRef, UserRef } from './api'
import type { YearTarget } from './indicator'

/** 分解矩阵行（一个执行单元）（API 16.3） */
export interface AllocationRow {
  unit: UnitRef
  owner: UserRef | null
  isHistorical: boolean
  yearTargets: YearTarget[]
}

/** 分解矩阵某年的合计校验结果 */
export interface AllocationYearSummary {
  year: number
  /** A+ 当年目标；比率 / 是否型为 null */
  indicatorTarget: number | null
  /** 各单元当年目标合计 */
  allocatedTarget: number | null
  /** 差值 = 合计 − 目标 */
  diff: number | null
}

/** GET /indicators/{id}/allocation 响应 */
export interface Allocation {
  indicatorId: string
  version: number
  isBalanced: boolean
  summary: AllocationYearSummary[]
  rows: AllocationRow[]
}

/** PUT /indicators/{id}/allocation 请求体 */
export interface SaveAllocationRequest {
  version: number
  rows: {
    unitId: string
    ownerId: string | null
    yearTargets: YearTarget[]
  }[]
}

/** POST /plans/{planId}/distribute 请求体 */
export interface DistributeRequest {
  /** 为空时下发所有可下发的指标 */
  indicatorIds?: string[]
  version: number
}

/** POST /plans/{planId}/distribute 响应（字段以后端为准，留够可空） */
export interface DistributeResult {
  indicatorCount?: number
  taskCount?: number
  generatedTaskCount?: number
  [key: string]: unknown
}
