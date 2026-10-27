import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { TaskDetail, TaskListItem, TaskQuery } from '@/types/task'

export function listTasks(query: TaskQuery = {}): Promise<PageResult<TaskListItem>> {
  return http.get('/tasks', { ...query })
}

export function getTask(taskId: string): Promise<TaskDetail> {
  return http.get(`/tasks/${taskId}`)
}

export function acceptTask(taskId: string, version: number, idempotencyKey: string): Promise<TaskDetail> {
  return http.post(`/tasks/${taskId}/accept`, { version }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}
