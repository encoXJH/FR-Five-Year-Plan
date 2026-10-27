/**
 * 飞书登录纯逻辑辅助（不依赖 DOM，便于单测）。
 * 参考飞书官方文档：
 * - 网页应用免登（获取登录预授权码 code）：https://open.feishu.cn/document/uYjL24iN/uITMuITMuITM
 * - 浏览器 OAuth 授权跳转：https://open.feishu.cn/document/uYjL24iN/ukDOukDOukDO
 */

/** 客户端环境（与 API 约定 X-Client-Type 一致） */
export type ClientEnv = 'WEB' | 'FEISHU_PC' | 'FEISHU_MOBILE'

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
  error: string | null
  errorDescription: string | null
}

/** 解析回调 URL 查询串（形如 "?code=xxx" 或 "?error=access_denied"） */
export function parseFeishuCallbackSearch(search: string): FeishuCallbackQuery {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)
  return {
    code: params.get('code'),
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

/** 从飞书 OAuth 授权地址中解析 app_id 参数 */
export function extractAppIdFromUrl(url: string): string | null {
  const match = /[?&]app_id=([^&#]+)/.exec(url)
  return match ? decodeURIComponent(match[1]) : null
}
