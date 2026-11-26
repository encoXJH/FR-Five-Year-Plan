import type { PageResult, UnitRef, UserRef } from './api'
import type {
  IndicatorLevel,
  IndicatorNature,
  IndicatorStatus,
  MeasureType,
  RollupMode,
  StatCaliber,
  SubmissionStatus,
  SubjectType,
  TargetDirection,
} from './enums'
import type { FileView } from './file'

/** 年度目标（API 3.6）：BOOLEAN 型计划年完成年为 true，其余年份为 null */
export interface YearTarget {
  year: number
  target: number | boolean | null
}

/** 指标（A / A+ 同表，level 区分） */
export interface Indicator {
  id: string
  planId: string
  parentId: string | null
  code: string
  name: string
  level: IndicatorLevel
  nature: IndicatorNature
  /** 主体（PRD：校级 / 专业群；A+ 沿用所属 A） */
  subjectType: SubjectType
  /** 仅 ADMIN 的 S 归属管理使用，普通业务页面不得展示 */
  directionId?: string | null
  directionCode?: string | null
  directionName?: string | null
  sort?: number | null
  measureType: MeasureType | null
  statCaliber: StatCaliber | null
  unitLabel: string | null
  targetDirection: TargetDirection | null
  rollupMode: RollupMode | null
  /** 仅 A 使用；false 时仅维护基础信息，不生成 A+ / B */
  isQuantifiable: boolean
  baselineValue: number | null
  /** 五年总量；比率 / 是否型为 null */
  totalTarget: number | null
  /** 总预算（万元）；仅 A 级有值，A+ 为 null */
  totalBudget?: number | null
  /** 已花预算（万元）：校审 APPROVED 认定支出合计，无则 0 */
  spentBudget?: number | null
  yearTargets: YearTarget[]
  owner: UserRef | null
  description: string | null
  evidenceRequirement: string | null
  /** 必须上传的材料最少数量；非量化为 null */
  minFileCount: number | null
  status: IndicatorStatus
  version: number
  createdAt: string
  updatedAt: string
}

/** GET /plans/{planId}/indicators?view=tree 树节点 */
export interface IndicatorTreeNode extends Indicator {
  children: IndicatorTreeNode[]
}

/** GET /indicators/{id} 详情（含下属 A+ 概要） */
export interface IndicatorDetail extends Indicator {
  parent: { id: string; code: string; name: string } | null
  children: Indicator[]
  bSummaries: Array<{ status: string; isHistorical: boolean }>
  /** 当前用户是否可编辑（服务端按数据范围判定） */
  editable: boolean
}

/** 指标列表查询参数 */
export interface IndicatorQuery {
  view?: 'tree' | 'list'
  level?: IndicatorLevel
  isQuantifiable?: boolean
  ownerId?: string
  keyword?: string
}

/** POST /plans/{planId}/indicators 请求体（API 16.2） */
export interface CreateIndicatorRequest {
  level: 'A'
  name: string
  nature: IndicatorNature
  directionId?: string | null
  isQuantifiable?: boolean
  measureType?: MeasureType | null
  statCaliber?: StatCaliber | null
  unitLabel?: string | null
  targetDirection?: TargetDirection | null
  rollupMode?: RollupMode | null
  description?: string | null
  minFileCount?: number | null
  baselineValue?: number | null
  totalTarget?: number | null
  yearTargets?: YearTarget[]
  ownerId?: string | null
  evidenceRequirement?: string | null
}

/** PATCH /indicators/{id} 请求体（不传表示不修改） */
export interface UpdateIndicatorRequest {
  name?: string
  nature?: IndicatorNature
  directionId?: string | null
  /** 仅 A 使用；不可量化 A 的量化字段提交为 null */
  isQuantifiable?: boolean
  measureType?: MeasureType | null
  statCaliber?: StatCaliber | null
  unitLabel?: string | null
  targetDirection?: TargetDirection | null
  rollupMode?: RollupMode | null
  description?: string | null
  baselineValue?: number | null
  ownerId?: string | null
  evidenceRequirement?: string | null
  minFileCount?: number | null
  version: number
}

/** PUT /indicators/{id}/year-targets 请求体 */
export interface UpdateYearTargetsRequest {
  totalTarget: number | null
  yearTargets: YearTarget[]
  version: number
}

/** PUT /indicators/{id}/budget 请求体；totalBudget 为 null 表示清除预算，reason 必填 */
export interface BudgetPutRequest {
  version: number
  totalBudget: number | null
  reason: string
}

/** 单条预算支出记录（含预算佐证材料） */
export interface BudgetExpenseItem {
  submissionId: string
  taskCode: string | null
  unit: UnitRef | null
  year: number
  expenseAmount: number | null
  approvedExpenseAmount: number | null
  status: SubmissionStatus
  submittedAt: string | null
  submittedBy: UserRef | null
  reviewedAt: string | null
  budgetFiles: FileView[]
}

/** GET /indicators/{id}/budget 响应：预算概览 + 支出明细分页 */
export interface IndicatorBudgetView {
  totalBudget: number | null
  spentBudget: number | null
  expenses: PageResult<BudgetExpenseItem>
}

/** 导入预览单行结果 */
export interface ImportPreviewRow {
  rowNum: number
  level: string | null
  code: string | null
  parentCode: string | null
  name: string | null
  totalTarget: number | null
  valid: boolean
  /** 校验错误信息（含错误码），空数组表示通过 */
  errors: string[]
}

/** POST .../import-preview 响应 */
export interface ImportPreviewResult {
  importId: string
  totalRows: number
  validRows: number
  invalidRows: number
  rows: ImportPreviewRow[]
}

/** POST .../import-confirm 请求体 */
export interface ImportConfirmRequest {
  importId: string
}

/** POST .../import-confirm 响应 */
export interface ImportConfirmResult {
  directionCount: number
  aCount: number
  /** 历史导入响应兼容字段，新流程不再使用 */
  aPlusCount?: number
}
