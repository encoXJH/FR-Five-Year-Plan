import type { UserRef } from './api'
import type { PlanSource, PlanStatus, PlanYearStatus } from './enums'

/** 计划（PRD FR-M1-03） */
export interface Plan {
  id: string
  name: string
  startYear: number
  endYear: number
  baselineYear: number
  /** 年度核算截止日（如 01-15，具体格式以后端返回为准） */
  settleDeadline: string | null
  status: PlanStatus
  /** 指标体系是否已定稿 */
  indicatorsFinalized: boolean
  finalizedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** GET /plans 首页卡片项（含整体完成率） */
export interface PlanCard {
  id: string
  name: string
  startYear: number
  endYear: number
  baselineYear: number
  status: PlanStatus
  /** 整体完成率（小数），null 显示 — */
  overallCompletionRate: number | null
  /** 本年度完成率（小数） */
  yearCompletionRate: number | null
  indicatorCountA: number
  indicatorCountAPlus: number
  taskCountB: number
  updatedAt: string
}

/** POST /plans 请求体 */
export interface CreatePlanRequest {
  name: string
  startYear: number
  endYear: number
  baselineYear: number
  settleDeadline?: string | null
  source: PlanSource
  /** source = TEMPLATE 时必填 */
  templateId?: string
  /** source = COPY 时必填 */
  sourcePlanId?: string
  /** source = COPY 时，是否同时复制分解数据 */
  copyAllocations?: boolean
}

/** PATCH /plans/{id} 请求体 */
export interface UpdatePlanRequest {
  name?: string
  startYear?: number
  endYear?: number
  baselineYear?: number
  settleDeadline?: string | null
  version: number
}

/** POST /plans/{id}/save-as-template 请求体 */
export interface SaveAsTemplateRequest {
  name: string
}

/** 计划年度（P03 年度步骤条） */
export interface PlanYear {
  year: number
  status: PlanYearStatus
  settledAt: string | null
}

/** GET /plans/{id} 详情 */
export interface PlanDetail extends Plan {
  years: PlanYear[]
}

/** 模板状态（API 第 9 章未列出，enable/disable 动作的派生状态） */
export type TemplateStatus = 'ENABLED' | 'DISABLED'

/** GET /templates 列表项 */
export interface PlanTemplate {
  id: string
  name: string
  description: string | null
  sourcePlanId: string | null
  sourcePlanName: string | null
  /** 模板周期年数（如 5） */
  durationYears: number | null
  indicatorCountA: number | null
  indicatorCountAPlus: number | null
  referenceCount: number
  /** 是否启用（展示时映射为 TemplateStatus：ENABLED / DISABLED） */
  isEnabled: boolean
  version: number
  createdAt: string
  updatedAt: string
}

/** 模板结构树节点（A → A+） */
export interface TemplateStructureNode {
  code: string | null
  name: string
  nodeType: 'A' | 'A_PLUS' | string
  measureType?: string | null
  statCaliber?: string | null
  unit?: string | null
  children?: TemplateStructureNode[]
}

/** 模板详情内容：只包含规划期与 A → A+ 指标结构，不暴露 S 配置 */
export interface TemplateContent {
  startYear: number
  endYear: number
  indicators: TemplateStructureNode[]
}

/** GET /templates/{id} 详情 */
export interface PlanTemplateDetail extends PlanTemplate {
  content: TemplateContent | null
}

/** PATCH /templates/{id} 请求体 */
export interface UpdateTemplateRequest {
  name?: string
  version: number
}

/** S 级任务，仅在 ADMIN 系统管理中展示 */
export interface Direction {
  id: string
  planId: string
  name: string
  sort: number | null
  leaderId: string | null
  leader: UserRef | null
  version: number
}

/** POST /plans/{planId}/directions 请求体 */
export interface CreateDirectionRequest {
  name: string
  sort?: number | null
  leaderId: string | null
}

/** PATCH /directions/{id} 请求体 */
export interface UpdateDirectionRequest {
  name?: string
  sort?: number | null
  leaderId?: string | null
  version: number
}
