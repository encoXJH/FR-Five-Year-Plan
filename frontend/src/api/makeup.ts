import { http } from '@/utils/request'
import type { MakeupItem } from '@/types/task'

export function listTaskMakeups(taskId: string): Promise<MakeupItem[]> {
  return http.get(`/tasks/${taskId}/makeups`)
}

export function completeMakeup(makeupId: string, version: number, idempotencyKey: string): Promise<MakeupItem> {
  return http.post(`/makeups/${makeupId}/complete`, { version }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}
