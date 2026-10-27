import type { StatCaliber } from '@/types/enums'
import type { AllocationYearSummary } from '@/types/allocation'

/** 矩阵单元格编辑态：年 → 目标值 */
export type MatrixTargets = Record<number, number | boolean | null>

export interface MatrixRow {
  unitId: string
  unitCode: string
  unitName: string
  ownerId: string | null
  isHistorical: boolean
  targets: MatrixTargets
}

/** 新增分解与转移接收共用：仅启用且允许承接新 B 的单元合格 */
export function canAssignNewB(unit: {
  isEnabled?: boolean | null
  isBAssignable?: boolean | null
}): boolean {
  return unit.isEnabled === true && unit.isBAssignable === true
}

/** 是否需要做「B 合计 = A+ 目标」求和校验（比率 / 是否型不校验，PRD 4.5） */
export function isSummableCaliber(caliber: StatCaliber): boolean {
  return caliber === 'INCREMENT' || caliber === 'STOCK' || caliber === 'GROWTH'
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

/** 是否全部年度平衡（非求和口径由调用方直接视为平衡） */
export function isSummaryBalanced(summary: AllocationYearSummary[]): boolean {
  return summary.every((item) => item.diff === null || item.diff === 0)
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
