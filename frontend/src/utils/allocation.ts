import type { StatCaliber, TaskStatus } from '@/types/enums'
import type {
  Allocation,
  AllocationYearSummary,
  SaveAllocationRequest,
} from '@/types/allocation'

/** 矩阵单元格编辑态：年 → 目标值 */
export type MatrixTargets = Record<number, number | boolean | null>

export interface MatrixRow {
  unitId: string
  unitCode: string
  unitName: string
  ownerId: string | null
  isHistorical: boolean
  taskStatus: TaskStatus | null
  targets: MatrixTargets
}

/** 共享编辑器向页面 / 抽屉同步的只读状态快照。 */
export interface AllocationEditorState {
  indicatorId: string
  rows: MatrixRow[]
  dirty: boolean
  balanced: boolean
  version: number
  serverBalanced: boolean | null
  loading: boolean
  saving: boolean
  canDistribute: boolean
  distributeBlockReasons: string[]
  hasAnyTarget: boolean
  missingOwners: MatrixRow[]
  ineligibleTargetRows: MatrixRow[]
  summary: AllocationYearSummary[]
  allocatedFiveYear: number
  fiveYearDiff: number | null
  lastSavedAt: string | null
}

export function emptyMatrixTargets(years: number[]): MatrixTargets {
  return Object.fromEntries(years.map((year) => [year, null]))
}

/** 服务端矩阵与本地编辑态之间只做这一份转换，供分解页和编辑抽屉共用。 */
export function allocationToMatrixRows(
  allocation: Allocation,
  years: number[],
  caliber: StatCaliber,
): MatrixRow[] {
  return (allocation.rows ?? []).map((row) => {
    const targets = emptyMatrixTargets(years)
    for (const year of years) {
      const value = row.yearTargets?.find((item) => item.year === year)?.target
      targets[year] = caliber === 'BOOLEAN'
        ? (value === true || value === 1 ? true : null)
        : (typeof value === 'number' ? value : null)
    }
    return {
      unitId: row.unit.id,
      unitCode: row.unit.code,
      unitName: row.unit.name,
      ownerId: row.owner?.id ?? null,
      isHistorical: row.isHistorical,
      taskStatus: row.taskStatus,
      targets,
    }
  })
}

export function buildSaveAllocationRequest(
  rows: MatrixRow[],
  years: number[],
  version: number,
): SaveAllocationRequest {
  return {
    version,
    rows: rows.map((row) => ({
      unitId: row.unitId,
      ownerId: row.ownerId,
      yearTargets: years.map((year) => ({ year, target: row.targets[year] ?? null })),
    })),
  }
}

/** 新增分解与转移接收共用：仅启用且允许承接新 B 的单元合格 */
export function canAssignNewB(unit: {
  isEnabled?: boolean | null
  isBAssignable?: boolean | null
}): boolean {
  return unit.isEnabled === true && unit.isBAssignable === true
}

/** 本地新增或待下发学院可编辑并进入本次下发；已生效及历史任务只读。 */
export function isPendingAllocationRow(row: Pick<MatrixRow, 'isHistorical' | 'taskStatus'>): boolean {
  return !row.isHistorical
    && (row.taskStatus === null || row.taskStatus === 'PENDING_DISTRIBUTION')
}

/** 是否需要做「B 合计 ≥ A 目标」求和校验（增长率 / 比率 / 是否型不校验，PRD 4.5） */
export function isSummableCaliber(caliber: StatCaliber): boolean {
  return caliber === 'INCREMENT' || caliber === 'STOCK'
}

export interface AutoDistributeResult {
  /** 已重新分配的年度 */
  filledYears: number[]
  /** 目标为空或只读行已满足而跳过的年度 */
  skippedYears: number[]
}

/**
 * 自动分配：把指标各年度目标扣除只读行后，平均写入可编辑行；余数逐行摊入，保证合计精确。
 * 只读行（历史 / 已下发）保持原值，继续参与合计；不修改任何行的负责人。
 * @param scale 分配刻度：增量型传 1（整数），存量型传 100（两位小数）
 */
export function distributeYearTargetsEvenly(
  rows: MatrixRow[],
  years: number[],
  indicatorTargets: MatrixTargets,
  scale = 100,
): AutoDistributeResult {
  const filledYears: number[] = []
  const skippedYears: number[] = []
  const editableRows = rows.filter(isPendingAllocationRow)
  for (const year of years) {
    const target = indicatorTargets[year]
    if (typeof target !== 'number' || editableRows.length === 0) {
      skippedYears.push(year)
      continue
    }
    const fixedTotal = rows
      .filter((row) => !isPendingAllocationRow(row))
      .reduce((sum, row) => {
        const value = row.targets[year]
        return sum + (typeof value === 'number' ? value : 0)
      }, 0)
    const remaining = target - fixedTotal
    if (remaining <= 0) {
      skippedYears.push(year)
      continue
    }
    const units = Math.round(remaining * scale)
    const base = Math.trunc(units / editableRows.length)
    const remainder = units - base * editableRows.length
    // 余数从第一行开始逐行 +1，避免较小目标全部落到最后一行
    editableRows.forEach((row, index) => {
      const extra = index < remainder ? 1 : 0
      row.targets[year] = (base + extra) / scale
    })
    filledYears.push(year)
  }
  return { filledYears, skippedYears }
}

/** 某行是否有任何目标（数值非 0 / 是否型有计划年） */
export function rowHasTarget(targets: MatrixTargets): boolean {
  return Object.values(targets).some((v) => v === true || (typeof v === 'number' && v !== 0))
}

/** 某行五年合计（仅数值目标参与求和） */
export function rowTotalTarget(targets: MatrixTargets): number {
  return Object.values(targets).reduce<number>(
    (sum, v) => sum + (typeof v === 'number' ? v : 0),
    0,
  )
}

/** 是否型某行的计划完成年（唯一为 true 的年份），无则 null */
export function rowBooleanPlanYear(targets: MatrixTargets): number | null {
  for (const [year, v] of Object.entries(targets)) {
    if (v === true) return Number(year)
  }
  return null
}

/**
 * 前端实时合计校验（体验层，最终以服务端 summary 为准，UI 规范 10.1）
 * @param indicatorTargets A+ 各年度目标
 */
export function computeYearSummary(
  rows: MatrixRow[],
  years: number[],
  indicatorTargets: MatrixTargets,
): AllocationYearSummary[] {
  return years.map((year) => {
    const allocated = rows.reduce<number>((sum, row) => {
      const v = row.targets[year]
      return sum + (typeof v === 'number' ? v : 0)
    }, 0)
    const raw = indicatorTargets[year]
    const indicatorTarget = typeof raw === 'number' ? raw : null
    return {
      year,
      indicatorTarget,
      allocatedTarget: allocated,
      diff: indicatorTarget === null ? null : allocated - indicatorTarget,
    }
  })
}

/** 是否全部年度可下发：允许超额，合计低于指标目标才不平衡 */
export function isSummaryBalanced(summary: AllocationYearSummary[]): boolean {
  return summary.every((item) => item.diff === null || item.diff >= 0)
}

/** 缺负责人的行（有目标但未指定学院负责人，下发时 50002） */
export function rowsMissingOwner(rows: MatrixRow[]): MatrixRow[] {
  return rows.filter((row) => !row.isHistorical && rowHasTarget(row.targets) && !row.ownerId)
}

/** 百分比输入框显示值（存储小数 ×100，1 位小数） */
export function percentToDisplay(value: number | null | undefined): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined
  return Math.round(value * 1000) / 10
}

/** 百分比输入框回存（÷100，最多 4 位小数） */
export function percentToStore(value: number | null | undefined): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null
  return Math.round(value * 10000) / 1000000
}
