import { EMPTY_DISPLAY, formatDate } from './format'

/** 历史任务只允许查看，所有执行写入口统一使用此守卫 */
export function canWriteTask(task: { isHistorical?: boolean | null } | null | undefined): boolean {
  return !!task && task.isHistorical !== true
}

export interface DeadlineDisplay {
  text: string
  /** 剩余 ≤ 7 天或已逾期（橙色 / 红色强调） */
  urgent: boolean
  overdue: boolean
}

/** 截止时间显示：剩余 ≤ 7 天附「剩 n 天」并标急；已过期标「已逾期」（UI 规范 3.5） */
export function formatDeadline(input: string | number | Date | null | undefined, now: Date = new Date()): DeadlineDisplay {
  const date = input instanceof Date ? input : input ? new Date(input) : null
  if (!date || Number.isNaN(date.getTime())) {
    return { text: EMPTY_DISPLAY, urgent: false, overdue: false }
  }
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const dateStart = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  const days = Math.round((dateStart - dayStart) / 86400000)
  const base = formatDate(date)
  if (days < 0) return { text: `${base}（已逾期）`, urgent: true, overdue: true }
  if (days <= 7) return { text: `${base}（剩 ${days} 天）`, urgent: true, overdue: false }
  return { text: base, urgent: false, overdue: false }
}

/** 完成率（小数）→ 进度条百分比（0–100，超 100 填满） */
export function progressPercentage(rate: number | null | undefined): number {
  if (typeof rate !== 'number' || !Number.isFinite(rate) || rate <= 0) return 0
  return Math.min(100, Math.round(rate * 1000) / 10)
}
