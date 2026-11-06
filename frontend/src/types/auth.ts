import type { UnitRef } from './api'
import type { Role } from './enums'

/** POST /auth/login 请求体 */
export interface LoginRequest {
  username: string
  password: string
}

/** 当前登录用户（登录响应与 /auth/me 中的 user） */
export interface CurrentUser {
  id: string
  name: string
  unit: UnitRef | null
  roles: Role[]
}

/** POST /auth/login 响应 data（API 约定 16.1 同构） */
export interface LoginResponse {
  accessToken: string
  accessTokenExpiresAt: string
  refreshToken: string
  refreshTokenExpiresAt: string
  user: CurrentUser
}

/** 飞书首次绑定挑战：此分支不包含可用 Token */
export interface FeishuBindingChallenge {
  accessToken: null
  accessTokenExpiresAt: null
  refreshToken: null
  refreshTokenExpiresAt: null
  user: null
  bindRequired: true
  bindingTicket: string
  bindingTicketExpiresAt: string
}

/** 飞书已绑定登录响应（Token 字段与密码登录一致） */
export type FeishuLoginSuccess = LoginResponse & {
  bindRequired: false
  bindingTicket: null
  bindingTicketExpiresAt: null
}

export type FeishuLoginResult = FeishuLoginSuccess | FeishuBindingChallenge

/** POST /auth/feishu/bind 请求体 */
export interface FeishuBindRequest {
  bindingTicket: string
  name: string
  employeeId: string
}

/** POST /auth/refresh 请求体 */
export interface RefreshRequest {
  refreshToken: string
}

/** POST /auth/refresh 响应 data（每次刷新都会轮换 refreshToken） */
export type RefreshResponse = Omit<LoginResponse, 'user'>

/** 数据范围（API 约定 4.3） */
export interface DataScope {
  /** ALL 全校 / INDICATOR 本人负责指标 / UNIT 本单元 */
  type: 'ALL' | 'INDICATOR' | 'UNIT'
  unitId: string | null
  indicatorIds: string[]
}

/** GET /auth/me 响应 data */
export interface MeResponse {
  user: CurrentUser
  roles: Role[]
  permissions: string[]
  dataScope: DataScope
}
