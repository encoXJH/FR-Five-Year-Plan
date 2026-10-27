import { http } from '@/utils/request'
import type {
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
export function fetchFeishuAuthorizeUrl(): Promise<unknown> {
  return http.get('/auth/feishu/authorize-url', undefined, { silent: true })
}

/** POST /auth/feishu/login（公开）：飞书免登 code 换 Token；静默，错误由调用方按码处理 */
export function feishuLogin(code: string, clientType: ClientEnv): Promise<LoginResponse> {
  return http.post(
    '/auth/feishu/login',
    { code },
    { silent: true, headers: { 'X-Client-Type': clientType } },
  )
}
