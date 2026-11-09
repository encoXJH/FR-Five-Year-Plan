import { http } from '@/utils/request'

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
