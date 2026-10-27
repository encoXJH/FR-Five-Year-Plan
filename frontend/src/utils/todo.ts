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
    case 'FILL':
      return todo.bizId ? `/tasks/${todo.bizId}/submit` : '/workbench'
    case 'REVIEW':
      return '/reviews'
    case 'ACCEPT':
      return '/workbench'
    case 'APPROVAL':
      return '/adjustments'
    default:
      return null
  }
}
