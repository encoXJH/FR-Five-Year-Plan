import type { StatCaliber } from '@/types/enums'
import type { MetricValue } from '@/types/submission'
import { EMPTY_DISPLAY, formatNumber, formatPercent } from './format'

/** 采集字段名（rate / growthRate / increment 为服务端只读，不在其列） */
export type MetricInputField = 'amount' | 'total' | 'numerator' | 'denominator' | 'percent' | 'achieved'

/** 统计口径 → MetricValue 采集字段（API 约定 3.5，其他字段必须为 null，否则 60006） */
export const CALIBER_INPUT_FIELDS: Record<StatCaliber, MetricInputField[]> = {
  INCREMENT: ['amount'],
  STOCK: ['total'],
  GROWTH: ['total'],
  RATIO: ['numerator', 'denominator'],
  DIRECT_PERCENT: ['percent'],
  BOOLEAN: ['achieved'],
}

/** 组装提交值：只保留本口径的采集字段，其余一律置 null */
export function buildMetricValue(caliber: StatCaliber, input: Partial<MetricValue>): MetricValue {
  const value: MetricValue = {
    amount: null,
    total: null,
    numerator: null,
    denominator: null,
    percent: null,
    achieved: null,
  }
  const writable = value as Record<MetricInputField, number | boolean | null>
  for (const field of CALIBER_INPUT_FIELDS[caliber]) {
    writable[field] = input[field] ?? null
  }
  return value
}

/** 占比实时计算：分母为 0 / 空值时返回 null（界面显示 —，UI 规范 3.4） */
export function computeRatio(
  numerator: number | null | undefined,
  denominator: number | null | undefined,
): number | null {
  if (typeof numerator !== 'number' || !Number.isFinite(numerator)) return null
  if (typeof denominator !== 'number' || !Number.isFinite(denominator) || denominator === 0) {
    return null
  }
  return numerator / denominator
}

/**
 * 认定值校验：认定值不能大于填报值（60005）
 * 数值字段逐字段比较；BOOLEAN 等非数值字段必须相等
 */
export function isApprovedValueValid(
  caliber: StatCaliber,
  submitted: MetricValue,
  approved: MetricValue,
): boolean {
  for (const field of CALIBER_INPUT_FIELDS[caliber]) {
    const sub = submitted[field]
    const app = approved[field]
    if (app === null || app === undefined) continue
    if (sub === null || sub === undefined) return false
    if (typeof sub === 'number' && typeof app === 'number') {
      if (app > sub) return false
    } else if (app !== sub) {
      return false
    }
  }
  return true
}

/** 核减量：第一个被核减的数值字段的差值；无核减返回 null */
export function approvedReduction(
  caliber: StatCaliber,
  submitted: MetricValue,
  approved: MetricValue,
): number | null {
  for (const field of CALIBER_INPUT_FIELDS[caliber]) {
    const sub = submitted[field]
    const app = approved[field]
    if (typeof sub === 'number' && typeof app === 'number' && app < sub) {
      return sub - app
    }
  }
  return null
}

/** MetricValue 按口径展示（填报记录、审核详情；比率 ×100 显示 %，UI 规范 3.4） */
export function formatMetricValue(
  value: MetricValue | null | undefined,
  caliber: StatCaliber,
  unitLabel?: string | null,
): string {
  if (!value) return EMPTY_DISPLAY
  const unit = unitLabel ? ` ${unitLabel}` : ''
  switch (caliber) {
    case 'INCREMENT': {
      const v = value.amount
      if (v === null || v === undefined) return EMPTY_DISPLAY
      return `${v > 0 ? '+' : ''}${formatNumber(v)}${unit}`
    }
    case 'STOCK':
    case 'GROWTH': {
      const v = value.total
      if (v === null || v === undefined) return EMPTY_DISPLAY
      return `${formatNumber(v)}${unit}`
    }
    case 'RATIO': {
      const n = value.numerator
      const d = value.denominator
      if (n === null || n === undefined || d === null || d === undefined) return EMPTY_DISPLAY
      const rate = computeRatio(n, d)
      const pair = `${formatNumber(n)} / ${formatNumber(d)}`
      return rate === null ? pair : `${pair}（${formatPercent(rate)}）`
    }
    case 'DIRECT_PERCENT': {
      return value.percent === null || value.percent === undefined
        ? EMPTY_DISPLAY
        : formatPercent(value.percent)
    }
    case 'BOOLEAN': {
      if (value.achieved === null || value.achieved === undefined) return EMPTY_DISPLAY
      return value.achieved ? '是' : '否'
    }
  }
}
