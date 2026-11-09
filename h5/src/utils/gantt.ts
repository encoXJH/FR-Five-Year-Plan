import type { Milestone } from '@/types/milestone'
import type { IndicatorTrend } from '@/types/stats'
import type { TaskDetail } from '@/types/task'
import type { EnumDict, StatCaliber } from '@/types/enums'
import { enumLabel } from '@/utils/enumLabels'
import { EMPTY_DISPLAY, formatDate, formatDateTime, formatNumber } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import { primaryTaskIndicator } from '@/utils/task'

export type GanttRowKind = 'indicator' | 'task' | 'milestone'
export type GanttTone = 'danger' | 'success' | 'primary' | 'warning' | 'adjust' | 'muted'

export interface GanttSegment {
  year: number
  target: number | boolean | null
  actualValue: number | null
  actualTotal?: number | null
  actualNumerator?: number | null
  actualDenominator?: number | null
  status: string | null
  dueDate?: string | null
  completedAt?: string | null
}

export interface GanttRow {
  id: string
  kind: GanttRowKind
  code: string
  name: string
  label: string
  caliber: StatCaliber | null
  unitLabel: string | null
  segments: GanttSegment[]
}

export const GANTT_TONE_COLORS: Record<GanttTone, string> = {
  danger: '#F56C6C',
  success: '#67C23A',
  primary: '#2B5BC0',
  warning: '#E6A23C',
  adjust: '#8E5CD9',
  muted: '#C0C4CC',
}

export function ganttTone(status: string | null | undefined): GanttTone {
  switch (status) {
    case 'NOT_ACHIEVED':
    case 'MAKEUP_PENDING':
    case 'LAGGING':
    case 'OVERDUE':
      return 'danger'
    case 'ACHIEVED':
    case 'EXCEEDED':
    case 'MADE_UP':
    case 'COMPLETED':
      return 'success'
    case 'IN_PROGRESS':
      return 'primary'
    case 'PENDING_REVIEW':
      return 'warning'
    case 'ADJUSTING':
    case 'POSTPONED':
      return 'adjust'
    default:
      return 'muted'
  }
}

export function trendToGanttRow(trend: IndicatorTrend): GanttRow {
  return {
    id: trend.indicatorId,
    kind: 'indicator',
    code: trend.code,
    name: trend.name,
    label: `${trend.code} ${trend.name}`,
    caliber: trend.statCaliber,
    unitLabel: trend.unitLabel,
    segments: trend.years.map((item) => ({
      year: item.year,
      target: item.target,
      actualValue: item.actualValue,
      actualTotal: item.actualTotal,
      actualNumerator: item.actualNumerator,
      actualDenominator: item.actualDenominator,
      status: item.achieveStatus,
    })),
  }
}

export function taskToGanttRow(task: TaskDetail): GanttRow {
  const indicator = primaryTaskIndicator(task)
  const name = indicator?.name ?? task.unit?.name ?? EMPTY_DISPLAY
  return {
    id: task.id,
    kind: 'task',
    code: task.code,
    name,
    label: `${task.code} ${task.unit?.name ?? name}`,
    caliber: indicator?.statCaliber ?? null,
    unitLabel: indicator?.unitLabel ?? null,
    segments: task.years.map((item) => ({
      year: item.year,
      target: item.target,
      actualValue: item.actualValue,
      actualNumerator: item.actualNumerator,
      actualDenominator: item.actualDenominator,
      status: item.yearStatus,
    })),
  }
}

export function milestonesToGanttRows(milestones: Milestone[], years: number[]): GanttRow[] {
  const validYears = new Set(years)
  return milestones.flatMap((item) => {
    const relatedYear = item.relatedYear ?? item.year ?? null
    if (relatedYear === null || !validYears.has(relatedYear)) return []
    return [{
      id: `milestone-${item.id}`,
      kind: 'milestone' as const,
      code: '里程碑',
      name: item.name,
      label: `里程碑 · ${item.name}`,
      caliber: null,
      unitLabel: null,
      segments: [{
        year: relatedYear,
        target: null,
        actualValue: null,
        status: item.status,
        dueDate: item.dueDate,
        completedAt: item.completedAt,
      }],
    }]
  })
}

export function formatGanttTarget(segment: GanttSegment, caliber: StatCaliber | null): string {
  if (caliber === 'BOOLEAN') return segment.target === true ? '计划完成' : EMPTY_DISPLAY
  return formatTargetValue(segment.target, caliber)
}

export function formatGanttActual(segment: GanttSegment, caliber: StatCaliber | null): string {
  if (caliber === 'BOOLEAN') {
    if (segment.actualValue === null || segment.actualValue === undefined) return EMPTY_DISPLAY
    return segment.actualValue === 1 ? '已完成' : '未完成'
  }
  return formatTargetValue(segment.actualValue, caliber)
}

export function formatGanttSegmentLines(row: GanttRow, segment: GanttSegment): string[] {
  if (row.kind === 'milestone') return []
  return [
    `目标 ${formatGanttTarget(segment, row.caliber)}`,
    `实际 ${formatGanttActual(segment, row.caliber)}`,
  ]
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

export function buildGanttTooltip(
  row: GanttRow,
  segment: GanttSegment,
  enums?: EnumDict,
): string {
  const lines = [`<strong>${escapeHtml(row.label)}</strong>`, `${segment.year} 年`]
  if (row.kind === 'milestone') {
    lines.push(`计划完成日期：${formatDate(segment.dueDate)}`)
    lines.push(`状态：${escapeHtml(segment.status ? enumLabel('MilestoneStatus', segment.status, enums) : EMPTY_DISPLAY)}`)
    lines.push(`实际完成时间：${formatDateTime(segment.completedAt)}`)
  } else {
    lines.push(`目标：${formatGanttTarget(segment, row.caliber)}${row.unitLabel && row.caliber !== 'RATIO' && row.caliber !== 'DIRECT_PERCENT' && row.caliber !== 'BOOLEAN' ? ` ${escapeHtml(row.unitLabel)}` : ''}`)
    lines.push(`实际（审核通过）：${formatGanttActual(segment, row.caliber)}`)
    if (row.caliber === 'RATIO') {
      lines.push(`分子：${formatNumber(segment.actualNumerator)} · 分母：${formatNumber(segment.actualDenominator)}`)
    }
    const kind = row.kind === 'task' ? 'TaskYearStatus' : 'AchieveStatus'
    lines.push(`状态：${escapeHtml(segment.status ? enumLabel(kind, segment.status, enums) : EMPTY_DISPLAY)}`)
  }
  return lines.join('<br/>')
}
