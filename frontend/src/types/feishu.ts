import type { NotificationType } from './enums'

export type FeishuBotPushStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED'

export type FeishuBotJob = 'PROGRESS' | 'LEADER_DIGEST'

export interface FeishuBotRule {
  code: string
  name: string
  description: string
}

export interface FeishuBotStatus {
  configured: boolean
  pushEnabled: boolean
  botEnabled: boolean
  timezone: string
  progressCron: string
  leaderDigestCron: string
  rules: FeishuBotRule[]
  todayPush: Record<FeishuBotPushStatus, number>
}

export interface FeishuBotLog {
  id: string
  userId: string
  receiverName: string | null
  type: NotificationType | string
  title: string
  content: string
  link: string | null
  feishuStatus: FeishuBotPushStatus | string
  feishuRetryCount: number
  feishuLastError: string | null
  createdAt: string
}

export type FeishuBotLogQuery = {
  page?: number
  pageSize?: number
  status?: FeishuBotPushStatus
  type?: NotificationType | string
  keyword?: string
  createdDate?: string
}

export interface FeishuBotRunResult {
  scanned: number
  created: number
  deduplicated: number
}
