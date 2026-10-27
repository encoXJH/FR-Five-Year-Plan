/** 数值与日期格式化（界面设计规范第 3 章） */

export const EMPTY_DISPLAY = '—'

const countFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })
const moneyFormatter = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const ratioFormatter = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const numberFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 })

function isValidNumber(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

/** 计数类：千分位、0 位小数，如 25,000 */
export function formatCount(value: number | null | undefined): string {
  if (!isValidNumber(value)) return EMPTY_DISPLAY
  return countFormatter.format(value)
}

/** 金额（万元）：千分位、2 位小数，如 2,860.00 */
export function formatMoney(value: number | null | undefined): string {
  if (!isValidNumber(value)) return EMPTY_DISPLAY
  return moneyFormatter.format(value)
}

/** 比值类（如生师比）：1 位小数，如 18.0 */
export function formatRatio(value: number | null | undefined): string {
  if (!isValidNumber(value)) return EMPTY_DISPLAY
  return ratioFormatter.format(value)
}

/** 百分比：小数 ×100、1 位小数、带 %，如 0.62 → 62.0% */
export function formatPercent(value: number | null | undefined): string {
  if (!isValidNumber(value)) return EMPTY_DISPLAY
  return `${(value * 100).toFixed(1)}%`
}

/** 增长率：1 位小数，正数带 +，负数带 -，如 +2.8% */
export function formatGrowth(value: number | null | undefined): string {
  if (!isValidNumber(value)) return EMPTY_DISPLAY
  if (value === 0) return '0.0%'
  const sign = value > 0 ? '+' : '-'
  return `${sign}${Math.abs(value * 100).toFixed(1)}%`
}

/** 差值：带符号；0 显示 0 ✓，如 +1 / -1 / 0 ✓ */
export function formatDiff(value: number | null | undefined): string {
  if (!isValidNumber(value)) return EMPTY_DISPLAY
  if (value === 0) return '0 ✓'
  return value > 0 ? `+${value}` : `${value}`
}

/** 通用数值：千分位，最多 4 位小数（指标数值约定，API 3.1） */
export function formatNumber(value: number | null | undefined): string {
  if (!isValidNumber(value)) return EMPTY_DISPLAY
  return numberFormatter.format(value)
}

/** 大屏大数字：≥ 10 万以“万”为单位、1 位小数，如 12.3 万 */
export function formatLargeNumber(value: number | null | undefined): string {
  if (!isValidNumber(value)) return EMPTY_DISPLAY
  if (Math.abs(value) >= 100000) {
    return `${(value / 10000).toFixed(1)} 万`
  }
  return formatCount(value)
}

type DateInput = string | number | Date | null | undefined

function toDate(input: DateInput): Date | null {
  if (input === null || input === undefined || input === '') return null
  const date = input instanceof Date ? input : new Date(input)
  return Number.isNaN(date.getTime()) ? null : date
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`
}

function formatDateParts(date: Date): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

/** 日期：YYYY-MM-DD */
export function formatDate(input: DateInput): string {
  const date = toDate(input)
  return date ? formatDateParts(date) : EMPTY_DISPLAY
}

/** 列表中的日期时间：YYYY-MM-DD HH:mm */
export function formatDateTime(input: DateInput): string {
  const date = toDate(input)
  if (!date) return EMPTY_DISPLAY
  return `${formatDateParts(date)} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/** 日志 / 审计：YYYY-MM-DD HH:mm:ss */
export function formatDateTimeFull(input: DateInput): string {
  const date = toDate(input)
  if (!date) return EMPTY_DISPLAY
  return `${formatDateTime(date)}:${pad2(date.getSeconds())}`
}

/** 消息、动态：24 小时内显示相对时间，否则显示日期 */
export function formatRelativeTime(input: DateInput, now: Date = new Date()): string {
  const date = toDate(input)
  if (!date) return EMPTY_DISPLAY
  const diffMs = now.getTime() - date.getTime()
  if (diffMs < 0) return formatDateTime(date)
  const minutes = Math.floor(diffMs / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return formatDate(date)
}

const DAY_MS = 86400000

/** 年度时间进度（小数）：当年已过天数（含今天）÷ 当年总天数，如 2026-10-22 → 295/365 ≈ 0.808 */
export function timeProgressOfYear(year: number, now: Date = new Date()): number {
  const start = new Date(year, 0, 1).getTime()
  const end = new Date(year + 1, 0, 1).getTime()
  const totalDays = Math.round((end - start) / DAY_MS)
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const passedDays = Math.round((todayStart - start) / DAY_MS) + 1
  return Math.min(Math.max(passedDays, 0), totalDays) / totalDays
}

/** 规划期时间进度（小数）：规划期已过天数（含今天）÷ 规划期总天数，如 2026-10-22 在 2026–2030 → 295/1826 ≈ 0.162 */
export function timeProgressOfPlan(startYear: number, endYear: number, now: Date = new Date()): number {
  const start = new Date(startYear, 0, 1).getTime()
  const end = new Date(endYear + 1, 0, 1).getTime()
  const totalDays = Math.round((end - start) / DAY_MS)
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const passedDays = Math.round((todayStart - start) / DAY_MS) + 1
  return Math.min(Math.max(passedDays, 0), totalDays) / totalDays
}
