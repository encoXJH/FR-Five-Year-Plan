import type { NotificationType } from './enums'

/** GET /notifications 站内消息条目（未读 = readAt 为 null） */
export interface NotificationItem {
  id: string
  type: NotificationType | string
  title: string
  content: string | null
  bizType: string | null
  bizId: string | null
  link: string | null
  readAt: string | null
  createdAt: string
}
