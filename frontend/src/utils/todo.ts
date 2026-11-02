import type { NotificationItem } from '@/types/notification'
import type { TodoGroup, TodoItem } from '@/types/todo'

/** 待办分组展示顺序与中文名（UI 规范 P16） */
export const TODO_GROUPS: { value: TodoGroup; label: string }[] = [
  { value: 'FILL', label: '待填报' },
  { value: 'REVIEW', label: '待审核' },
  { value: 'ACCEPT', label: '待确认接收' },
  { value: 'APPROVAL', label: '待审批' },
]

export function todoGroupLabel(group: string): string {
  return TODO_GROUPS.find((g) => g.value === group)?.label ?? group
}

/** 分组排序：按 TODO_GROUPS 顺序，未知分组排最后（组内保持后端 todoSince 升序） */
export function sortTodoItems(items: TodoItem[]): TodoItem[] {
  const order = new Map<string, number>(TODO_GROUPS.map((g, i) => [g.value, i]))
  return [...items].sort(
    (a, b) => (order.get(a.type) ?? TODO_GROUPS.length) - (order.get(b.type) ?? TODO_GROUPS.length),
  )
}

/**
 * 待办「去处理」跳转（bizId 语义：FILL / ACCEPT = 任务 id，REVIEW = 填报 id，APPROVAL = 调整申请 id）：
 * 待填报 → 任务填报页；待审核 → 审核中心；待确认接收 → 学院工作台；待审批 → 调整管理。
 */
export function resolveTodoLink(todo: TodoItem): string | null {
  switch (todo.type) {
    case 'FILL': {
      if (!todo.bizId) return '/workbench'
      const year = todo.year == null ? '' : `?year=${todo.year}`
      return `/tasks/${todo.bizId}/submit${year}`
    }
    case 'REVIEW':
      return todo.bizId ? `/reviews?submissionId=${todo.bizId}` : '/reviews'
    case 'ACCEPT': {
      if (!todo.bizId) return '/workbench'
      const params = new URLSearchParams({ taskId: todo.bizId })
      if (todo.year != null) params.set('year', String(todo.year))
      return `/workbench?${params.toString()}`
    }
    case 'APPROVAL':
      return todo.bizId ? `/adjustments?adjustmentId=${todo.bizId}` : '/adjustments'
    default:
      return null
  }
}

/** 通知优先使用后端深链；历史空链接按业务对象回退，无法定位时由调用方阻止仅标记已读。 */
export function resolveNotificationLink(notification: NotificationItem): string | null {
  if (notification.link?.trim()) return notification.link
  if (!notification.bizId) return null
  switch (notification.bizType) {
    case 'submission':
      return `/reviews?submissionId=${notification.bizId}`
    case 'task':
      return `/workbench?taskId=${notification.bizId}`
    case 'adjustment':
      return `/adjustments?adjustmentId=${notification.bizId}`
    case 'task_year':
      return '/workbench'
    default:
      return null
  }
}
