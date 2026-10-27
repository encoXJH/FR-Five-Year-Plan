import type { UserRef } from './api'
import type {
  IndicatorLevel,
  IndicatorNature,
  IndicatorStatus,
  MeasureType,
  RollupMode,
  StatCaliber,
  SubjectType,
  TargetDirection,
} from './enums'

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
  yearTargets: YearTarget[]
  owner: UserRef | null
  description: string | null
  evidenceRequirement: string | null
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
  level: IndicatorLevel
  /** 新增 A+ 时必填 */
  parentId?: string
  name: string
  nature: IndicatorNature
  isQuantifiable?: boolean
  measureType?: MeasureType | null
  statCaliber?: StatCaliber | null
  unitLabel?: string | null
  targetDirection?: TargetDirection | null
  rollupMode?: RollupMode | null
  description?: string | null
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
  version: number
}

/** PUT /indicators/{id}/year-targets 请求体 */
export interface UpdateYearTargetsRequest {
  totalTarget: number | null
  yearTargets: YearTarget[]
  version: number
}

/** 导入预览单行结果 */
export interface ImportPreviewRow {
  row: number
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
  createdCount: number
}
