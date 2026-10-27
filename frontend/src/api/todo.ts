import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { NotificationItem } from '@/types/notification'
import type { TodosResult } from '@/types/todo'

/** GET /todos（API 15.9，已登录） */
export function getTodos(): Promise<TodosResult> {
  return http.get('/todos')
}

export type NotificationQuery = {
  /** 只查未读 */
  unreadOnly?: boolean
  /** 按消息类型过滤（NotificationType） */
  type?: string
  page?: number
  pageSize?: number
}

export type NotificationPage = PageResult<NotificationItem> & { unreadCount: number }

/** GET /notifications（API 15.9，已登录；响应附 unreadCount 未读总数） */
export function listNotifications(params: NotificationQuery): Promise<NotificationPage> {
  return http.get('/notifications', params)
}

/** 未读消息数（顶栏角标）：响应的 unreadCount 不受筛选影响，恒为未读总数 */
export async function getUnreadNotificationCount(): Promise<number> {
  const result = await listNotifications({ unreadOnly: true, page: 1, pageSize: 1 })
  return result.unreadCount
}

/** POST /notifications/{notificationId}/read（API 15.9；返回更新后的条目） */
export function markNotificationRead(notificationId: string): Promise<NotificationItem> {
  return http.post(`/notifications/${notificationId}/read`)
}

/** POST /notifications/read-all（API 15.9；返回 { readCount }） */
export function markAllNotificationsRead(): Promise<{ readCount: number }> {
  return http.post('/notifications/read-all')
}
