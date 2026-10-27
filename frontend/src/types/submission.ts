import type { UnitRef, UserRef } from './api'
import type { ReviewResult, StatCaliber, SubmissionStatus } from './enums'
import type { FileInfo } from './file'

/**
 * 指标值对象 MetricValue（API 约定 3.5）
 * 由统计口径决定采集哪个字段，其他字段为 null；rate / growthRate / increment 为服务端只读计算字段
 */
export interface MetricValue {
  amount?: number | null
  total?: number | null
  numerator?: number | null
  denominator?: number | null
  percent?: number | null
  achieved?: boolean | null
  /** 只读：RATIO 的占比（小数） */
  rate?: number | null
  /** 只读：GROWTH 的增长率（小数） */
  growthRate?: number | null
  /** 只读：STOCK 的较上年增量 */
  increment?: number | null
}

/** 审核记录（随填报详情返回） */
export interface ReviewRecord {
  id: string
  reviewer: UserRef | null
  result: ReviewResult
  comment: string | null
  approvedValue: MetricValue | null
  /** 是否管理员代审（API 第 10 章敏感操作标识） */
  isProxy?: boolean | null
  createdAt: string
}

/** GET /tasks/{taskId}/submissions 列表项 */
export interface SubmissionListItem {
  id: string
  taskId: string
  year: number
  status: SubmissionStatus
  value: MetricValue
  approvedValue?: MetricValue | null
  remark?: string | null
  /** DIRECT_PERCENT 数据来源说明（字段名以后端为准，见开发决策记录） */
  sourceNote?: string | null
  fileIds?: string[]
  files?: FileInfo[]
  version: number
  submittedAt?: string | null
  createdAt: string
  updatedAt?: string
  createdBy?: UserRef | null
  /** 是否管理员代填（API 第 10 章敏感操作标识） */
  isProxy?: boolean | null
  /** 代填原因（代填时由后端返回） */
  proxyReason?: string | null
  /** 列表接口可能直接带最近一次审核意见 */
  reviews?: ReviewRecord[]
}

/** GET /submissions/{id} 详情（含材料、审核记录） */
export interface SubmissionDetail extends SubmissionListItem {
  files: FileInfo[]
  reviews: ReviewRecord[]
  task?: {
    id: string
    code: string
    isHistorical: boolean
    indicator?: {
      id: string
      code: string
      name: string
      statCaliber?: StatCaliber | null
      unitLabel?: string | null
      evidenceRequirement?: string | null
    } | null
    unit?: UnitRef | null
    owner?: UserRef | null
  } | null
}

/** POST /tasks/{taskId}/submissions 请求体（API 16.4） */
export interface CreateSubmissionRequest {
  year: number
  value: MetricValue
  remark?: string | null
  sourceNote?: string | null
  fileIds?: string[]
  /** 管理员代填原因（敏感操作，API 第 10 章；后端契约字段名 proxyReason，is_proxy=1） */
  proxyReason?: string | null
}

/** PATCH /submissions/{id} 请求体（不传表示不修改） */
export interface UpdateSubmissionRequest {
  value?: MetricValue
  remark?: string | null
  sourceNote?: string | null
  fileIds?: string[]
  proxyReason?: string | null
  version: number
}

/** GET /reviews/pending 查询参数 */
export interface PendingReviewQuery {
  planId?: string
  indicatorId?: string
  unitId?: string
  submittedAtFrom?: string
  submittedAtTo?: string
  keyword?: string
  page?: number
  pageSize?: number
}

/** GET /reviews/pending 列表项 */
export interface PendingReviewItem {
  id: string
  year: number
  status: SubmissionStatus
  value: MetricValue
  remark?: string | null
  sourceNote?: string | null
  submittedAt: string | null
  fileCount?: number | null
  task?: {
    id: string
    code: string
    isHistorical: boolean
    indicator?: {
      id: string
      code: string
      name: string
      statCaliber?: StatCaliber | null
      unitLabel?: string | null
    } | null
    unit?: UnitRef | null
    owner?: UserRef | null
  } | null
  version?: number
}

/** POST /submissions/{id}/review 请求体（API 15.5） */
export interface ReviewRequest {
  result: ReviewResult
  comment?: string | null
  /** 核减认定值（不能大于填报值，60005） */
  approvedValue?: MetricValue | null
  version?: number
  /** 管理员代审原因（敏感操作，API 第 10 章） */
  reason?: string | null
}

/** POST /submissions/batch-review 请求体 */
export interface BatchReviewRequest {
  submissionIds: string[]
  comment?: string | null
  reason?: string | null
}

/**
 * 批量审核单条结果（后端契约：逐条独立事务，逐条返回；
 * 响应可能是数组或 { results / items } 包裹，api 层已归一化为数组）
 */
export interface BatchReviewItemResult {
  submissionId: string
  success: boolean
  code: number
  message: string
}
