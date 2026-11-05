import type { IndicatorLevel, MeasureType, StatCaliber } from '@/types/enums'
import type { YearTarget } from '@/types/indicator'
import type { StatsTreeNode } from '@/types/stats'
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

/** 实际完成值显示：是否型显示已完成/未完成，比率类按百分比，其余按数值 */
export function formatActualValue(
  actual: number | null | undefined,
  caliber: StatCaliber | null | undefined,
): string {
  if (actual === null || actual === undefined) return EMPTY_DISPLAY
  if (caliber === 'BOOLEAN') return actual > 0 ? '已完成' : '未完成'
  if (isPercentCaliber(caliber)) return formatPercent(actual)
  return formatNumber(actual)
}

/** 年度进度条百分比：优先使用后端完成率；无完成率但目标为 0 且有完成值时按 100%，封顶 100 */
export function completionPercent(
  completionRate: number | null | undefined,
  actualValue: number | null | undefined,
  target: number | boolean | null | undefined,
): number {
  if (completionRate !== null && completionRate !== undefined && Number.isFinite(completionRate)) {
    return Math.max(0, Math.min(100, Math.round(completionRate * 1000) / 10))
  }
  const actual = Number(actualValue)
  if (typeof target === 'number' && target === 0 && Number.isFinite(actual) && actual > 0) return 100
  return 0
}

/** 超额完成数量：实际值大于数值目标时返回差值，否则 null */
export function exceededAmount(
  actualValue: number | null | undefined,
  target: number | boolean | null | undefined,
): number | null {
  if (typeof target !== 'number' || actualValue === null || actualValue === undefined) return null
  const actual = Number(actualValue)
  if (!Number.isFinite(actual) || actual <= target) return null
  return Math.round((actual - target) * 10000) / 10000
}

/** 完成程度：无目标返回 null；0 或空值 red(none)，0–100 yellow(partial)，≥100 green(complete) */
export type CompletionLevel = 'none' | 'partial' | 'complete'

export function completionLevel(
  percent: number | null | undefined,
  hasTarget: boolean,
): CompletionLevel | null {
  if (!hasTarget) return null
  if (percent === null || percent === undefined || !Number.isFinite(percent) || percent <= 0) return 'none'
  return percent >= 100 ? 'complete' : 'partial'
}

export function completionLevelClass(level: CompletionLevel | null): string {
  return level ? `completion-value--${level}` : ''
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

export function parseExpandedIndicatorIds(raw: string | null): string[] {
  if (!raw) return []
  try {
    const value: unknown = JSON.parse(raw)
    if (!Array.isArray(value)) return []
    return [...new Set(value.filter((id): id is string => typeof id === 'string' && /^\d+$/.test(id)))]
  } catch {
    return []
  }
}

export function serializeExpandedIndicatorIds(ids: Iterable<string>): string {
  return JSON.stringify([...new Set(ids)])
}

export function parseDirectionIdQuery(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

export interface DirectionalIndicator {
  directionId?: string | null
  directionName?: string | null
  sort?: number | null
}

export interface IndicatorDirectionGroup<T> {
  key: string
  directionId: string | null
  directionName: string
  sort: number | null
  firstIndex: number
  indicators: T[]
}

function directionSortValue(sort: number | null | undefined): number {
  return typeof sort === 'number' && Number.isFinite(sort) ? sort : Number.POSITIVE_INFINITY
}

/** 方向按组内最小 sort、首次出现顺序排列；未分配方向固定置尾。 */
export function groupIndicatorsByDirection<T extends DirectionalIndicator>(
  indicators: T[],
): IndicatorDirectionGroup<T>[] {
  const groups = new Map<string, IndicatorDirectionGroup<T>>()
  const inputOrder = new Map<T, number>()
  indicators.forEach((indicator, index) => inputOrder.set(indicator, index))

  indicators.forEach((indicator, firstIndex) => {
    const directionId = indicator.directionId || null
    const directionName = directionId ? (indicator.directionName?.trim() ?? '') : ''
    const key = JSON.stringify([directionId, directionName])
    let group = groups.get(key)
    if (!group) {
      group = {
        key,
        directionId,
        directionName: directionId ? directionName || '未命名建设方向' : '未分配建设方向',
        sort: null,
        firstIndex,
        indicators: [],
      }
      groups.set(key, group)
    }
    group.indicators.push(indicator)
    const sort = directionSortValue(indicator.sort)
    if (Number.isFinite(sort) && (group.sort === null || sort < group.sort)) group.sort = sort
  })

  return [...groups.values()]
    .map((group) => ({
      ...group,
      indicators: [...group.indicators].sort((left, right) =>
        directionSortValue(left.sort) - directionSortValue(right.sort)
        || (inputOrder.get(left) ?? 0) - (inputOrder.get(right) ?? 0)),
    }))
    .sort((left, right) => {
      if (left.directionId === null && right.directionId !== null) return 1
      if (left.directionId !== null && right.directionId === null) return -1
      return directionSortValue(left.sort) - directionSortValue(right.sort)
        || left.firstIndex - right.firstIndex
    })
}

export function parseCollapsedDirectionKeys(raw: string | null): string[] {
  if (!raw) return []
  try {
    const value: unknown = JSON.parse(raw)
    if (!Array.isArray(value)) return []
    return [...new Set(value.filter((key): key is string => typeof key === 'string' && key.length > 0))]
  } catch {
    return []
  }
}

export function serializeCollapsedDirectionKeys(keys: Iterable<string>): string {
  return JSON.stringify([...new Set(keys)])
}

export interface StatsTreeIndex {
  indicators: Map<string, StatsTreeNode>
  tasks: Map<string, StatsTreeNode>
}

/** 统计树 A 按指标 ID、B 按 taskId 索引；B 节点自身 ID 是 task_year ID。 */
export function indexStatsTree(nodes: StatsTreeNode[]): StatsTreeIndex {
  const index: StatsTreeIndex = { indicators: new Map(), tasks: new Map() }
  const walk = (items: StatsTreeNode[]): void => {
    for (const node of items) {
      if (node.level === 'A') index.indicators.set(node.id, node)
      if (node.level === 'B' && node.taskId) index.tasks.set(node.taskId, node)
      walk(node.children ?? [])
    }
  }
  walk(nodes)
  return index
}

