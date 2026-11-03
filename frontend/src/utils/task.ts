import { EMPTY_DISPLAY, formatDate } from './format'
import type { TaskIndicatorRef, TaskListItem } from '@/types/task'

/** B 的主指标统一展示所属 A；旧数据缺少 topIndicator 时回退到原关联指标。 */
export function primaryTaskIndicator(
  task: Pick<TaskListItem, 'indicator' | 'topIndicator'> | null | undefined,
): TaskIndicatorRef | null {
  return task?.topIndicator ?? task?.indicator ?? null
}

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

/** 年度完成率简单平均：未产生完成值的任务按 0 计入分母。 */
export function averageTaskYearCompletion(
  tasks: Pick<TaskListItem, 'completionRate'>[],
): number | null {
  if (tasks.length === 0) return null
  const total = tasks.reduce((sum, task) => {
    const rate = task.completionRate
    return sum + (typeof rate === 'number' && Number.isFinite(rate) ? rate : 0)
  }, 0)
  return total / tasks.length
}

/** 完成率（小数）→ 进度条百分比（0–100，超 100 填满） */
export function progressPercentage(rate: number | null | undefined): number {
  if (typeof rate !== 'number' || !Number.isFinite(rate) || rate <= 0) return 0
  return Math.min(100, Math.round(rate * 1000) / 10)
}
