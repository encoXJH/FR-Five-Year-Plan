import type { IndicatorLevel, MeasureType, StatCaliber } from '@/types/enums'
import type { YearTarget } from '@/types/indicator'
import { EMPTY_DISPLAY, formatNumber, formatPercent } from './format'

/** 计量类型 → 允许的统计口径（API 第 9 章，组合不合法返回 40007） */
export const CALIBER_BY_MEASURE: Record<MeasureType, StatCaliber[]> = {
  NUMBER: ['INCREMENT', 'STOCK'],
  BOOLEAN: ['BOOLEAN'],
  PERCENT_QUANTIFIABLE: ['GROWTH', 'RATIO'],
  PERCENT_DIRECT: ['DIRECT_PERCENT'],
}

export function isValidCaliberCombo(measureType: MeasureType, caliber: StatCaliber): boolean {
  return CALIBER_BY_MEASURE[measureType].includes(caliber)
}

/** 非量化 A 只维护基础信息；A+ 与可量化 A 需要完整量化配置 */
export function hasQuantitativeFields(level: IndicatorLevel, isQuantifiable: boolean): boolean {
  return level === 'A_PLUS' || (level === 'A' && isQuantifiable)
}

/** 比率类口径（占比 / 直填百分比）：目标按小数展示为百分比 */
export function isPercentCaliber(caliber: StatCaliber | null | undefined): boolean {
  return caliber === 'RATIO' || caliber === 'DIRECT_PERCENT'
}

/** 目标值显示（指标总表年度列、五年目标列） */
export function formatTargetValue(
  target: number | boolean | null | undefined,
  caliber: StatCaliber | null | undefined,
): string {
  if (target === null || target === undefined) return EMPTY_DISPLAY
  if (typeof target === 'boolean') return target ? '是' : EMPTY_DISPLAY
  if (isPercentCaliber(caliber)) return formatPercent(target)
  return formatNumber(target)
}

/** 年度目标数组 → Map<year, target> */
export function yearTargetMap(yearTargets: YearTarget[] | null | undefined): Map<number, number | boolean | null> {
  const map = new Map<number, number | boolean | null>()
  for (const item of yearTargets ?? []) {
    map.set(item.year, item.target)
  }
  return map
}

/** BOOLEAN 型的计划完成年份（target === true 的那一年） */
export function booleanPlanYear(yearTargets: YearTarget[] | null | undefined): number | null {
  const hit = (yearTargets ?? []).find((item) => item.target === true)
  return hit ? hit.year : null
}

/** 数值型年度目标之和（BOOLEAN 年份不参与求和） */
export function sumYearTargets(yearTargets: YearTarget[] | null | undefined): number {
  return (yearTargets ?? []).reduce((sum, item) => {
    return sum + (typeof item.target === 'number' ? item.target : 0)
  }, 0)
}
