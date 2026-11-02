import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { TaskDetail, TaskListItem, TaskQuery } from '@/types/task'

export function listTasks(query: TaskQuery = {}): Promise<PageResult<TaskListItem>> {
  return http.get('/tasks', { ...query })
}

/** 按相同过滤条件分页读取全部任务，供指标展开与甘特图使用。 */
export async function listAllTasks(query: TaskQuery = {}): Promise<TaskListItem[]> {
  const pageSize = 100
  const items: TaskListItem[] = []
  let page = 1
  let total = Number.POSITIVE_INFINITY
  while (items.length < total) {
    const result = await listTasks({ ...query, page, pageSize })
    total = result.total
    items.push(...result.items)
    if (result.items.length === 0) break
    page += 1
  }
  return items
}

export function getTask(taskId: string): Promise<TaskDetail> {
  return http.get(`/tasks/${taskId}`)
}

export function acceptTask(taskId: string, version: number, idempotencyKey: string): Promise<TaskDetail> {
  return http.post(`/tasks/${taskId}/accept`, { version }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}
