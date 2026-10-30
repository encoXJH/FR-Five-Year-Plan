import type { Numeric, ValueKind } from '@/types/api'

export function formatDate(value?: string | null): string {
  if (!value) return '—'
  return value.length >= 10 ? value.slice(0, 10) : value
}

export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const normalized = value.replace('T', ' ')
  return normalized.length >= 19 ? normalized.slice(0, 19) : normalized
}

export function formatNumber(value: Numeric): string {
  const number = Number(value)
  if (!Number.isFinite(number)) return String(value)
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(number)
}

export function isBlank(value: unknown): boolean {
  return value === null || value === undefined || value === ''
}

export function semanticText(raw?: string | null): string | null {
  if (raw === null || raw === undefined || raw.trim() === '') return null
  const text = raw.trim()
  if (['—', '-', '－', '不适用'].includes(text)) return '不适用'
  if (text === '无') return '未分配'
  if (['待补', '待补充', '无法自动核算'].includes(text)) return '待补口径'
  return null
}

export interface ValueOptions {
  raw?: string | null
  text?: string | null
  boolValue?: boolean | number | null
  valueKind?: ValueKind
  unit?: string | null
  emptyText?: string
}

export function formatBusinessValue(value: Numeric | null | undefined, options: ValueOptions = {}): string {
  const { raw, text, boolValue, valueKind, unit, emptyText = '未提供' } = options
  const semantic = semanticText(raw)
  if (semantic) return semantic
  if (valueKind === 'BOOLEAN' || boolValue !== null && boolValue !== undefined) {
    if (boolValue === null || boolValue === undefined) return emptyText
    return boolValue === true || boolValue === 1 ? '是' : '否'
  }
  if (!isBlank(value)) {
    const rendered = formatNumber(value as Numeric)
    return unit && unit !== '—' ? `${rendered} ${unit}` : rendered
  }
  if (!isBlank(text)) return String(text)
  if (!isBlank(raw)) return String(raw)
  if (valueKind === 'NONE' || unit === '—') return '不适用'
  return emptyText
}

export function formatFileSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}

export function percentWidth(rate?: Numeric | null): number {
  if (isBlank(rate)) return 0
  const value = Number(rate)
  return Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0
}

export function remainingGap(target?: Numeric | null, recognized?: Numeric | null, covered?: Numeric | null): number | null {
  if (isBlank(target)) return null
  const result = Number(target) - Number(recognized ?? 0) - Number(covered ?? 0)
  return Number.isFinite(result) ? Math.max(0, result) : null
}
