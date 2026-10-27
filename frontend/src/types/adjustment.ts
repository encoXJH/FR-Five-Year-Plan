import type { FieldError, UnitRef, UserRef } from './api'
import type {
  AdjustmentStatus,
  AdjustmentType,
  ApprovalNode,
  Decision,
  FileCategory,
  StatCaliber,
  TranscodeStatus,
} from './enums'

/** 调整明细项（API 约定 16.5 items；TARGET_CHANGE 用 year + newTarget） */
export interface AdjustmentItem {
  fromYear?: number | null
  /** 延期 / 延期+转移时的目标年份；TARGET_CHANGE 时为被调整年度 */
  toYear?: number | null
  /** 调整数量（BOOLEAN 延期不带数量） */
  amount?: number | null
  /** TARGET_CHANGE：被调整年度 */
  year?: number | null
  /** TARGET_CHANGE：新目标值（比率类小数） */
  newTarget?: number | null
}

/** 调整前 / 后快照行（后端严格为 { taskCode | indicatorCode, year, target }；year=null 表示五年总量条目） */
export interface AdjustmentSnapshotRow {
  taskCode?: string | null
  indicatorCode?: string | null
  year: number | null
  target: number | null
}

/** 调整前后快照（POST /adjustments 响应、详情均使用） */
export interface AdjustmentSnapshot {
  before: AdjustmentSnapshotRow[]
  after: AdjustmentSnapshotRow[]
}

/** POST /adjustments/preview 请求体（试算，不保存），与发起申请同构 */
export interface AdjustmentPreviewRequest {
  type: AdjustmentType
  /** B 任务级调整（延期 / 转移 / 延期+转移） */
  taskId?: string
  /** 指标级调整（TARGET_CHANGE，A / A+，仅管理员） */
  indicatorId?: string
  /** TRANSFER / POSTPONE_TRANSFER 必填 */
  toUnitId?: string
  items?: AdjustmentItem[]
  /** 申请原因（后端 preview 与 create 共用请求模型，@NotBlank 必填；决策记录十八节） */
  reason?: string
}

/** POST /adjustments/preview 响应 */
export interface AdjustmentPreviewResult {
  /** 全部校验通过 */
  valid: boolean
  /** 守恒 / 额度校验错误，空数组表示通过 */
  errors?: FieldError[] | null
  snapshot: AdjustmentSnapshot | null
  /** 转移类接收方是否已有 B（false = 生效时自动新建） */
  toTaskExists?: boolean | null
  /** 转出方任务当前已冻结的调整额度（在途申请占用，70004；后端缺省时不展示） */
  frozenAmount?: number | null
}

/** POST /adjustments 请求体（幂等） */
export interface CreateAdjustmentRequest extends AdjustmentPreviewRequest {
  /** 申请原因（必填，≤ 2000 字） */
  reason: string
  fileIds?: string[]
}

/** GET /adjustments 的 scope 参数（API 15.6） */
export type AdjustmentScope = 'MINE' | 'TODO' | 'ALL'

export interface AdjustmentQuery {
  scope?: AdjustmentScope
  planId?: string
  status?: AdjustmentStatus
  type?: AdjustmentType
  page?: number
  pageSize?: number
}

export interface AdjustmentTaskRef {
  id: string
  code: string
  isHistorical: boolean
}

export interface AdjustmentIndicatorRef {
  id: string
  code: string
  name: string
  statCaliber?: StatCaliber | null
  unitLabel?: string | null
}

/** GET /adjustments 列表项（items 为简要明细） */
export interface AdjustmentListItem {
  id: string
  planId?: string
  type: AdjustmentType
  status: AdjustmentStatus
  /** 当前审批环节（审批中才有） */
  currentNode?: ApprovalNode | null
  task?: AdjustmentTaskRef | null
  indicator?: AdjustmentIndicatorRef | null
  fromUnit?: UnitRef | null
  toUnit?: UnitRef | null
  items?: AdjustmentItem[]
  reason?: string | null
  applicant?: UserRef | null
  version: number
  createdAt: string
  /** 生效时间（APPROVED 后） */
  effectiveAt?: string | null
}

/** 审批环节记录（详情 steps） */
export interface ApprovalStepRecord {
  id?: string
  seq?: number
  node: ApprovalNode
  /** 期望处理人（校级负责人 / 校领导） */
  expectedApprover?: UserRef | null
  /** 期望处理单元（接收学院确认环节） */
  expectedUnit?: UnitRef | null
  decision?: Decision | null
  comment?: string | null
  decidedAt?: string | null
  decidedBy?: UserRef | null
}

/** 附件（后端 FileView；transcodeStatus 预览抽屉需要，缺省按 null 处理） */
export interface AdjustmentFileRef {
  id: string
  fileName: string
  size: number
  ext?: string | null
  category: FileCategory
  transcodeStatus: TranscodeStatus | null
}

/** GET /adjustments/{id} 详情 */
export interface AdjustmentDetail extends AdjustmentListItem {
  snapshot?: AdjustmentSnapshot | null
  steps?: ApprovalStepRecord[]
  files?: AdjustmentFileRef[]
  /** 本申请冻结的转出方额度（审批中才有；后端缺省时不展示） */
  frozenAmount?: number | null
  withdrawnAt?: string | null
  updatedAt?: string
}

/** POST /adjustments/{id}/decision 请求体（幂等，API 15.6） */
export interface AdjustmentDecisionRequest {
  decision: Decision
  /** 驳回必填 */
  comment?: string | null
  version: number
}
