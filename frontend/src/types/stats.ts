import type { UnitRef, UserRef } from './api'
import type {
  AchieveStatus,
  PlanYearStatus,
  StatCaliber,
  TaskYearStatus,
} from './enums'

// ===== 统计（API 约定 15.8；比率为小数，前端只负责 ×100 显示） =====

/** 滞后预警项（时间进度 − 完成进度 > 阈值） */
export interface WarningItem {
  taskId: string
  taskCode: string
  indicatorName: string
  unit: UnitRef | null
  year: number
  completionRate: number | null
  timeProgress: number | null
  lagGap: number | null
}

/** GET /plans/{planId}/stats/overview?year=（year 缺省为五年累计口径） */
export interface StatsOverview {
  planId: string
  /** 统计年度；null 表示五年累计 */
  year: number | null
  /** 时间进度（小数） */
  timeProgress: number | null
  /** 完成率（小数，计数制：达标任务数 ÷ 有正目标任务数） */
  overallCompletionRate: number | null
  /** 五年累计完成率（小数）：各 A 指标累计完成率的算术平均；五年总量才是 100% */
  cumulativeCompletionRate: number | null
  constraintTotal: number
  constraintAchieved: number
  developmentTotal: number
  developmentAchieved: number
  taskCount: number
  achievedCount: number
  notAchievedCount: number
  inProgressCount: number
  makeupPendingCount: number
  madeUpCount: number
  warningCount: number
  warnings: WarningItem[]
}

/** GET /plans/{planId}/stats/by-unit?year= 列表项 */
export interface UnitStatItem {
  unit: UnitRef | null
  taskCount: number
  achievedCount: number
  completionRate: number | null
  /** 五年累计完成率（小数）：该单元可见任务累计完成率的平均 */
  cumulativeRate: number | null
  rank: number
}

/** GET /plans/{planId}/stats/by-indicator?year= 列表项 */
export interface IndicatorStat {
  indicatorId: string
  code: string
  name: string
  aPlusCount: number
  taskCount: number
  achievedCount: number
  completionRate: number | null
  cumulativeRate: number | null
}

/** GET /plans/{planId}/stats/tree?year= 下钻树节点（A → A+ → B） */
export interface StatsTreeNode {
  id: string
  code: string
  name: string
  level: 'A' | 'A_PLUS' | 'B'
  nature?: string | null
  statCaliber?: StatCaliber | null
  unitLabel?: string | null
  targetDirection?: string | null
  target: number | boolean | null
  actualValue: number | null
  completionRate: number | null
  achieveStatus: AchieveStatus | null
  /** 五年累计完成值（INCREMENT 各年之和；STOCK/GROWTH 最新总量；RATIO/DIRECT_PERCENT 最新比率；BOOLEAN 0/1） */
  cumulativeActual?: number | null
  /** 五年累计完成率（小数）= 累计完成 ÷ 五年总量目标 */
  cumulativeRate?: number | null
  taskId?: string | null
  unit?: UnitRef | null
  yearStatus?: TaskYearStatus | null
  isAchieved?: boolean | null
  isHistorical: boolean
  children?: StatsTreeNode[]
}

/** GET /indicators/{indicatorId}/trend 年度点 */
export interface TrendYearItem {
  year: number
  target: number | boolean | null
  actualValue: number | null
  actualTotal: number | null
  completionRate: number | null
  achieveStatus: AchieveStatus | null
}

/** GET /indicators/{indicatorId}/trend */
export interface IndicatorTrend {
  indicatorId: string
  code: string
  name: string
  statCaliber: StatCaliber | null
  unitLabel: string | null
  baselineValue: number | null
  totalTarget: number | null
  /** 五年累计完成值（INCREMENT 各年之和；STOCK/GROWTH 最新总量；比率类最新值；BOOLEAN 0/1） */
  cumulativeActual: number | null
  /** 五年累计完成率（小数）= 累计完成 ÷ 五年总量目标 */
  cumulativeRate: number | null
  years: TrendYearItem[]
}

// ===== 核算 =====

/** 年度核算结果明细条目 */
export interface SettlementItem {
  taskId: string
  taskCode: string
  indicator: { id: string; code: string; name: string } | null
  unit: UnitRef | null
  target: number | boolean | null
  actualValue: number | null
  completionRate: number | null
  isAchieved: boolean | null
  yearStatus: TaskYearStatus
  makeupId?: string | null
  makeupGap?: number | null
  makeupStatus?: string | null
}

/** GET /plans/{planId}/years/{year}/settlement（未 LOCKED 时 items 为空数组） */
export interface YearSettlement {
  planId: string
  year: number
  planYearStatus: PlanYearStatus
  settledAt: string | null
  settledBy: UserRef | null
  lastUnlockReason: string | null
  items: SettlementItem[]
}

/** 五年核算明细项 */
export interface FinalItem {
  indicatorId: string
  code: string
  name: string
  nature: string | null
  statCaliber: StatCaliber | null
  unitLabel: string | null
  totalTarget: number | null
  fiveYearActual: number | null
  completionRate: number | null
  isAchieved: boolean | null
}

/** GET /plans/{planId}/final-settlement / POST final-settle 响应 */
export interface FinalSettlement {
  planId: string
  finalSettledAt: string | null
  finalSettledBy: UserRef | null
  indicatorCount: number
  achievedCount: number
  items: FinalItem[]
}

// ===== 大屏 =====

/** 大屏年度趋势项（任务计数口径） */
export interface ScreenYearTrend {
  year: number
  taskCount: number
  achievedCount: number
  completionRate: number | null
}

export interface ScreenAdjustmentStats {
  totalCount: number
  pendingCount: number
  approvedCount: number
  postponeCount: number
  transferCount: number
}

/** 大屏核心数字条（规范 9.1 ④）：A 类 · A+ 类 · B 任务 · 佐证材料 · 本年审核通过 */
export interface ScreenCoreNumbers {
  indicatorACount: number
  indicatorAPlusCount: number
  taskCount: number
  materialCount: number
  approvedThisYearCount: number
}

/** 大屏最近动态（审核流水） */
export interface ScreenActivity {
  time: string
  operatorName: string
  taskCode: string
  year: number
  result: string
}

/** GET /screens/{planId}?year= 一次聚合响应（服务端缓存，核算写操作后失效） */
export interface ScreenData {
  plan: { id: string; name: string; startYear: number; endYear: number } | null
  year: number | null
  generatedAt: string
  overview: StatsOverview
  byUnit: UnitStatItem[]
  byIndicator: IndicatorStat[]
  trend: ScreenYearTrend[]
  adjustmentStats: ScreenAdjustmentStats | null
  coreNumbers: ScreenCoreNumbers | null
  recentActivities: ScreenActivity[]
}
