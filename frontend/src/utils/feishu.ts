/**
 * 飞书登录纯逻辑辅助（不依赖 DOM，便于单测）。
 * 参考飞书官方文档：
 * - 网页应用免登（获取登录预授权码 code）：https://open.feishu.cn/document/uYjL24iN/uITMuITMuITM
 * - 浏览器 OAuth 授权跳转：https://open.feishu.cn/document/uYjL24iN/ukDOukDOukDO
 */

/** 客户端环境（与 API 约定 X-Client-Type 一致） */
export type ClientEnv = 'WEB' | 'FEISHU_PC' | 'FEISHU_MOBILE'

/** 管理页面展示中文执行时间，避免直接暴露 Quartz cron 表达式。 */
export function formatFeishuBotSchedule(cron: string): string {
  const value = cron.trim()
  if (!value) return '未配置执行时间'
  const match = /^0\s+(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+\?$/.exec(value)
  if (!match) return '按自定义周期执行'
  const minute = Number(match[1])
  const hour = Number(match[2])
  if (minute > 59 || hour > 23) return '按自定义周期执行'
  return `每天 ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

/** 依据 UA 探测当前环境：UA 含 Lark / Feishu 视为飞书客户端，再按移动端关键字细分 */
export function detectClientEnv(ua: string): ClientEnv {
  const s = ua.toLowerCase()
  if (!s.includes('lark') && !s.includes('feishu')) return 'WEB'
  if (/mobile|android|iphone|ipad|ipod/.test(s)) return 'FEISHU_MOBILE'
  return 'FEISHU_PC'
}

/** 是否处于飞书客户端内置浏览器 */
export function isFeishuUA(ua: string): boolean {
  return detectClientEnv(ua) !== 'WEB'
}

/** 视为「飞书未配置 / 不可用」的错误码：20006 免登失败占位（阶段 1 起）、90001 飞书接口调用失败 */
export const FEISHU_UNAVAILABLE_CODES: readonly number[] = [20006, 90001]

export function isFeishuUnavailableCode(code: number | undefined | null): boolean {
  return typeof code === 'number' && FEISHU_UNAVAILABLE_CODES.includes(code)
}

/** 飞书授权回调页的查询参数解析结果 */
export interface FeishuCallbackQuery {
  code: string | null
  state: string | null
  error: string | null
  errorDescription: string | null
}

/** 解析回调 URL 查询串（形如 "?code=xxx" 或 "?error=access_denied"） */
export function parseFeishuCallbackSearch(search: string): FeishuCallbackQuery {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)
  return {
    code: params.get('code'),
    state: params.get('state'),
    error: params.get('error'),
    errorDescription: params.get('error_description'),
  }
}

/** GET /auth/feishu/authorize-url 的响应（字段名做别名兼容，契约假设见开发决策记录） */
export interface FeishuAuthorizeInfo {
  /** 浏览器跳转的飞书 OAuth 授权地址 */
  url: string
  /** 飞书应用 AppID（JSAPI 免登需要；缺省时尝试从 url 中解析） */
  appId: string | null
}

/** 归一化 authorize-url 响应：兼容 url / authorizeUrl、appId / app_id 命名 */
export function normalizeAuthorizeInfo(raw: unknown): FeishuAuthorizeInfo | null {
  if (!raw || typeof raw !== 'object') return null
  const record = raw as Record<string, unknown>
  const url = record.url ?? record.authorizeUrl ?? record.authorize_url
  if (typeof url !== 'string' || url.length === 0) return null
  const appId = record.appId ?? record.app_id
  return {
    url,
    appId: typeof appId === 'string' && appId.length > 0 ? appId : extractAppIdFromUrl(url),
  }
}

/** OAuth state 的会话存储键；仅用于防止回调 CSRF，不保存 Token 或绑定票据 */
export const FEISHU_OAUTH_STATE_KEY = 'fzsu.feishu.oauthState'
export const FEISHU_OAUTH_CONTEXT_KEY = 'fzsu.feishu.oauthContext'
export const FEISHU_OAUTH_CONTEXT_MAX_AGE_MS = 10 * 60 * 1000

export interface FeishuOAuthStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface FeishuOAuthContext {
  state: string
  returnTo: string | null
  createdAt: number
}

export function clearFeishuOAuthContext(storage: FeishuOAuthStorage = sessionStorage): void {
  storage.removeItem(FEISHU_OAUTH_STATE_KEY)
  storage.removeItem(FEISHU_OAUTH_CONTEXT_KEY)
}

export function saveFeishuOAuthContext(
  state: string,
  returnTo: string | null,
  storage: FeishuOAuthStorage = sessionStorage,
  now = Date.now(),
): void {
  const context: FeishuOAuthContext = { state, returnTo, createdAt: now }
  storage.setItem(FEISHU_OAUTH_STATE_KEY, state)
  storage.setItem(FEISHU_OAUTH_CONTEXT_KEY, JSON.stringify(context))
}

/** 回调只允许消费一次；state 不符、上下文损坏或超过 10 分钟均拒绝。 */
export function consumeFeishuOAuthContext(
  actualState: string | null,
  storage: FeishuOAuthStorage = sessionStorage,
  now = Date.now(),
): FeishuOAuthContext | null {
  const expectedState = storage.getItem(FEISHU_OAUTH_STATE_KEY)
  const raw = storage.getItem(FEISHU_OAUTH_CONTEXT_KEY)
  clearFeishuOAuthContext(storage)
  if (!raw || !isFeishuOAuthStateValid(actualState, expectedState)) return null
  try {
    const context = JSON.parse(raw) as Partial<FeishuOAuthContext>
    if (typeof context.state !== 'string' || context.state !== actualState || typeof context.createdAt !== 'number'
      || !Number.isFinite(context.createdAt)) return null
    const age = now - context.createdAt
    if (age < 0 || age > FEISHU_OAUTH_CONTEXT_MAX_AGE_MS) return null
    if (context.returnTo !== null && typeof context.returnTo !== 'string') return null
    return { state: context.state, returnTo: context.returnTo ?? null, createdAt: context.createdAt }
  } catch {
    return null
  }
}

export function isFeishuOAuthStateValid(actual: string | null, expected: string | null): boolean {
  return actual !== null && expected !== null && actual.length > 0 && actual === expected
}

/** 从飞书 OAuth 授权地址中解析应用 ID（兼容 app_id / client_id） */
export function extractAppIdFromUrl(url: string): string | null {
  const match = /[?&](?:app_id|client_id)=([^&#]+)/.exec(url)
  return match ? decodeURIComponent(match[1]) : null
}
