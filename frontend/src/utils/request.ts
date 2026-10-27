import axios, { AxiosError } from 'axios'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ApiResponse, FieldError } from '@/types/api'
import type { RefreshResponse } from '@/types/auth'
import { detectClientEnv } from '@/utils/feishu'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 置 true 时拦截器不再弹出错误提示，由调用方自行处理（用于探测类 / 免登请求） */
    silent?: boolean
  }
}

function isSilent(config: InternalAxiosRequestConfig | undefined): boolean {
  return config?.silent === true
}

/** X-Client-Type 默认值：飞书客户端内按 UA 上报 FEISHU_PC / FEISHU_MOBILE（API 约定 5.2） */
function defaultClientType(): string {
  if (typeof window === 'undefined' || typeof window.navigator?.userAgent !== 'string') {
    return 'WEB'
  }
  return detectClientEnv(window.navigator.userAgent)
}

const ACCESS_TOKEN_KEY = 'fzsu.accessToken'
const REFRESH_TOKEN_KEY = 'fzsu.refreshToken'

export function getAccessToken(): string | null {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY)
}

export function saveTokens(accessToken: string, refreshToken: string): void {
  sessionStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  sessionStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens(): void {
  sessionStorage.removeItem(ACCESS_TOKEN_KEY)
  sessionStorage.removeItem(REFRESH_TOKEN_KEY)
}

/** 业务错误（HTTP 200 但 code !== 0，或按 6.5 处理后仍需调用方感知的错误） */
export class ApiError extends Error {
  readonly code: number
  readonly requestId: string
  readonly fieldErrors: FieldError[]

  constructor(body: Pick<ApiResponse<unknown>, 'code' | 'message' | 'requestId'> & { errors?: FieldError[] }) {
    super(body.message)
    this.name = 'ApiError'
    this.code = body.code
    this.requestId = body.requestId
    this.fieldErrors = body.errors ?? []
  }
}

const service = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

/** 已经按 6.5 重试过一次的请求，防止无限循环 */
const retriedRequests = new WeakSet<InternalAxiosRequestConfig>()

let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new Error('refresh token missing')
  }
  const { data: body } = await axios.post<ApiResponse<RefreshResponse>>(
    '/api/v1/auth/refresh',
    { refreshToken },
    { headers: { 'X-Client-Type': 'WEB' }, timeout: 15000 },
  )
  if (body.code !== 0) {
    throw new ApiError(body)
  }
  saveTokens(body.data.accessToken, body.data.refreshToken)
  return body.data.accessToken
}

function redirectToLogin(): void {
  clearTokens()
  if (window.location.pathname !== '/login') {
    window.location.assign('/login')
  }
}

/** 最近一次出错的 requestId，供 403 结果页等展示（界面设计规范 6.3 / 7.11） */
const LAST_REQUEST_ID_KEY = 'fzsu.lastRequestId'

function rememberRequestId(requestId: string): void {
  if (requestId) {
    sessionStorage.setItem(LAST_REQUEST_ID_KEY, requestId)
  }
}

export function getLastRequestId(): string {
  return sessionStorage.getItem(LAST_REQUEST_ID_KEY) ?? ''
}

/** 复制 requestId 到剪贴板 */
function copyRequestId(requestId: string): void {
  void navigator.clipboard?.writeText(requestId).then(() => ElMessage.success('已复制请求编号'))
}

/** 系统错误弹窗（界面设计规范 7.11）：错误码括号 + 等宽 requestId + 复制 */
function showSystemError(message: string, code: number, requestId: string): void {
  rememberRequestId(requestId)
  const html = [
    `<div>${message || '系统繁忙，请稍后重试'}（${code}）</div>`,
    requestId
      ? `<div style="margin-top:8px;font-family:Consolas,Menlo,monospace;font-size:12px;color:#909399;word-break:break-all;">请求编号：${requestId} <a href="javascript:void 0" id="fzsu-copy-rid" style="color:#2B5BC0;">复制</a></div>`
      : '',
    '<div style="margin-top:4px;font-size:12px;color:#909399;">如需帮助，请将请求编号发送给平台管理员。</div>',
  ].join('')
  void ElMessageBox.alert(html, '操作失败', {
    confirmButtonText: '我知道了',
    dangerouslyUseHTMLString: true,
    callback: () => {},
  })
  // 挂载后给复制链接绑定事件
  setTimeout(() => {
    document.getElementById('fzsu-copy-rid')?.addEventListener('click', () => copyRequestId(requestId))
  }, 0)
}

/** 版本冲突弹窗（界面设计规范 7.11 / 10.4）：409 + 10005 */
function showConflictError(): void {
  void ElMessageBox.alert('数据已被他人修改，请刷新后重试', '版本冲突', {
    confirmButtonText: '刷新',
    type: 'warning',
  }).then(() => {
    window.location.reload()
  })
}

service.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  if (!config.headers['X-Client-Type']) {
    config.headers['X-Client-Type'] = defaultClientType()
  }
  config.headers['X-Request-Id'] = crypto.randomUUID()
  return config
})

service.interceptors.response.use(
  (response: AxiosResponse) => {
    const body = response.data as ApiResponse<unknown> | undefined
    if (body && typeof body === 'object' && typeof body.code === 'number') {
      if (body.code === 0) {
        return body.data as AxiosResponse
      }
      if (!isSilent(response.config)) {
        ElMessage.error(body.message || '操作失败')
      }
      return Promise.reject(new ApiError(body))
    }
    return response.data as AxiosResponse
  },
  async (error: unknown) => {
    if (!(error instanceof AxiosError)) {
      return Promise.reject(error)
    }
    const { response, config } = error
    // 上传暂停 / 取消等主动中止的请求静默处理，由调用方自行感知
    if (error.code === 'ERR_CANCELED') {
      return Promise.reject(error)
    }
    if (!response || !config) {
      if (!isSilent(config)) {
        ElMessage.error('网络异常，请检查网络连接后重试')
      }
      return Promise.reject(error)
    }

    const silent = isSilent(config)
    const body = response.data as Partial<ApiResponse<unknown>> | undefined
    const code = typeof body?.code === 'number' ? body.code : undefined
    const message = typeof body?.message === 'string' ? body.message : ''
    const requestId = typeof body?.requestId === 'string' ? body.requestId : ''
    const isLoginRequest = config.url?.includes('/auth/login') ?? false

    // 401：未登录 / Token 无效或过期（API 约定 6.5）
    if (response.status === 401) {
      if (isLoginRequest) {
        if (!silent) {
          ElMessage.error(message || '用户名或密码错误')
        }
        return Promise.reject(new ApiError({ code: code ?? 20008, message, requestId }))
      }
      if (silent) {
        // 探测 / 免登请求由调用方处理，不触发全局跳转
        return Promise.reject(new ApiError({ code: code ?? 20001, message, requestId }))
      }
      if (code === 20002 && !retriedRequests.has(config) && getRefreshToken()) {
        retriedRequests.add(config)
        try {
          if (!refreshPromise) {
            refreshPromise = refreshAccessToken().finally(() => {
              refreshPromise = null
            })
          }
          const newToken = await refreshPromise
          config.headers.Authorization = `Bearer ${newToken}`
          return service(config)
        } catch {
          redirectToLogin()
          return Promise.reject(error)
        }
      }
      ElMessage.error(message || '登录状态已失效，请重新登录')
      redirectToLogin()
      return Promise.reject(error)
    }

    // 403：没有功能权限或数据权限
    if (response.status === 403) {
      rememberRequestId(requestId)
      if (!silent) {
        ElMessage.warning(message || '没有操作权限')
      }
      return Promise.reject(new ApiError({ code: code ?? 20004, message, requestId }))
    }

    // 409 + 10005：乐观锁版本冲突（调用方置 silent 自行处理时跳过）
    if (response.status === 409 && code === 10005) {
      if (!silent) {
        showConflictError()
      }
      return Promise.reject(new ApiError({ code, message, requestId }))
    }

    // 5xx：系统错误弹窗 + requestId 复制（界面设计规范 7.11）
    if (response.status >= 500) {
      if (!silent) {
        showSystemError(message, code ?? 10099, requestId)
      }
      return Promise.reject(new ApiError({ code: code ?? 10099, message, requestId }))
    }

    // 400 / 422 及其他：展示服务端 message，调用方可读取 fieldErrors 映射到表单
    if (!silent) {
      ElMessage.error(message || '请求失败')
    }
    return Promise.reject(
      new ApiError({ code: code ?? response.status, message, requestId, errors: body?.errors }),
    )
  },
)

type RequestParams = Record<string, string | number | boolean | undefined>

/** 解包后的请求方法：成功时直接得到 data，失败时 reject ApiError 并统一提示 */
export const http = {
  get<T>(url: string, params?: RequestParams, config?: AxiosRequestConfig): Promise<T> {
    return service.get(url, { ...config, params }) as unknown as Promise<T>
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.post(url, data, config) as unknown as Promise<T>
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.put(url, data, config) as unknown as Promise<T>
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.patch(url, data, config) as unknown as Promise<T>
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.delete(url, config) as unknown as Promise<T>
  },
}

export default service
