import { http } from '@/utils/request'
import type {
  FeishuBindRequest,
  FeishuLoginResult,
  LoginRequest,
  LoginResponse,
  MeResponse,
  RefreshRequest,
  RefreshResponse,
} from '@/types/auth'
import type { ClientEnv } from '@/utils/feishu'

export function login(data: LoginRequest): Promise<LoginResponse> {
  return http.post('/auth/login', data)
}

export function refresh(data: RefreshRequest): Promise<RefreshResponse> {
  return http.post('/auth/refresh', data)
}

export function logout(): Promise<null> {
  return http.post('/auth/logout')
}

export function fetchMe(): Promise<MeResponse> {
  return http.get('/auth/me')
}

/**
 * GET /auth/feishu/authorize-url（公开）。
 * 静默探测：后端未配置飞书时返回 20006 / 90001，调用方隐藏飞书入口，不弹错误提示。
 * 响应字段做别名归一化（url / authorizeUrl、appId / app_id，见 utils/feishu.normalizeAuthorizeInfo）。
 */
export function fetchFeishuAuthorizeUrl(state?: string): Promise<unknown> {
  return http.get('/auth/feishu/authorize-url', state ? { state } : undefined, { silent: true })
}

/** POST /auth/feishu/login（公开）：已绑定返回 Token，首次登录返回绑定挑战 */
export function feishuLogin(code: string, clientType: ClientEnv): Promise<FeishuLoginResult> {
  return http.post(
    '/auth/feishu/login',
    { code },
    { silent: true, headers: { 'X-Client-Type': clientType } },
  )
}

/** POST /auth/feishu/bind（公开）：首次绑定经校内教师信息核验后返回 Token */
export function feishuBind(data: FeishuBindRequest, clientType: ClientEnv): Promise<LoginResponse> {
  return http.post(
    '/auth/feishu/bind',
    data,
    { silent: true, headers: { 'X-Client-Type': clientType } },
  )
}
