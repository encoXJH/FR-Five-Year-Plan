import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  FeishuBotJob,
  FeishuBotLog,
  FeishuBotLogQuery,
  FeishuBotRunResult,
  FeishuBotStatus,
} from '@/types/feishu'

/**
 * 飞书集成（P15 飞书集成 Tab；API 约定 15.11）。
 * GET /integrations/feishu/status（system:manage）：应用配置状态与最近同步结果；
 * 接口异常时前端降级为仅展示可推算的回调地址。
 */

/** 组织同步结果摘要（与后端 SyncOrgView 一致；缺省时前端只展示已有项） */
export interface FeishuSyncSummary {
  departmentCount?: number | null
  feishuUserCount?: number | null
  matchedCount?: number | null
  openIdBoundCount?: number | null
  nameUpdatedCount?: number | null
  unitUpdatedCount?: number | null
  unmatchedCount?: number | null
  syncedAt?: string | null
}

/** GET /integrations/feishu/status 响应（契约假设） */
export interface FeishuIntegrationStatus {
  /** 后端是否已配置飞书 AppID / AppSecret */
  configured: boolean
  /** 脱敏后的 AppID（如 cli_a1b2****） */
  appIdMasked?: string | null
  /** 免登 / OAuth 回调地址（后端给定时优先展示） */
  authorizeCallbackUrl?: string | null
  /** 事件回调地址（缺省时前端按当前域名推算） */
  eventCallbackUrl?: string | null
  /** 最近一次组织同步时间 */
  lastSyncAt?: string | null
  /** 最近一次组织同步结果 */
  lastSyncResult?: FeishuSyncSummary | null
}

/** GET /integrations/feishu/status（静默，接口未就绪时返回 null） */
export async function fetchFeishuIntegrationStatus(): Promise<FeishuIntegrationStatus | null> {
  try {
    return await http.get('/integrations/feishu/status', undefined, { silent: true })
  } catch {
    return null
  }
}

/** POST /integrations/feishu/sync-org（system:manage，幂等；同步为异步任务时返回受理信息） */
export function syncFeishuOrg(idempotencyKey: string): Promise<FeishuSyncSummary> {
  return http.post(
    '/integrations/feishu/sync-org',
    {},
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

/** GET /integrations/feishu/bot/status：机器人开关、调度规则与今日推送统计 */
export function fetchFeishuBotStatus(): Promise<FeishuBotStatus> {
  return http.get('/integrations/feishu/bot/status')
}

/** GET /integrations/feishu/bot/logs：按推送状态、通知类型和创建日期查询日志 */
export function listFeishuBotLogs(params: FeishuBotLogQuery): Promise<PageResult<FeishuBotLog>> {
  return http.get('/integrations/feishu/bot/logs', params)
}

/** POST /integrations/feishu/bot/run：手动执行进度检查或校级汇总（幂等） */
export function runFeishuBot(job: FeishuBotJob, idempotencyKey: string): Promise<FeishuBotRunResult> {
  return http.post(
    '/integrations/feishu/bot/run',
    { job },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}
