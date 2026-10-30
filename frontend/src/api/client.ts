import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ListResult, PageQuery } from '@/types/api'

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30_000,
  withCredentials: true,
  headers: { 'X-Requested-With': 'XMLHttpRequest' },
})

export interface ApiErrorDetail {
  code?: string
  message?: string
  details?: unknown
}
export interface ApiErrorShape {
  code?: string
  message?: string
  detail?: string | ApiErrorDetail | Array<{ msg?: string; message?: string; loc?: unknown }>
  details?: unknown
  errors?: unknown
  current_data?: unknown
}

function detailLines(value: unknown, path = ''): string[] {
  if (value === null || value === undefined || value === '') return []
  if (typeof value === 'string') return [path ? `${path}：${value}` : value]
  if (typeof value === 'number' || typeof value === 'boolean') return [path ? `${path}：${String(value)}` : String(value)]
  if (Array.isArray(value)) return value.flatMap((item) => detailLines(item, path))
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>
    const loc = Array.isArray(record.loc) ? record.loc.filter((item) => item !== 'body').map(String).join('.') : ''
    const currentPath = loc || path
    const message = record.msg || record.message
    if (message) return detailLines(message, currentPath)
    if (record.details !== undefined) return detailLines(record.details, currentPath)
    return Object.entries(record).filter(([key]) => !['code', 'loc'].includes(key)).flatMap(([key, item]) => {
      const nextPath = currentPath ? `${currentPath}.${key}` : key
      return detailLines(item, nextPath)
    })
  }
  return []
}

export function resolveApiErrorMessage(payload: unknown, status?: number): string {
  const data = payload && typeof payload === 'object' ? payload as ApiErrorShape : {}
  const detail = data.detail
  const nested = detail && typeof detail === 'object' && !Array.isArray(detail) ? detail : undefined
  const primary = typeof payload === 'string' ? payload : data.message || (typeof detail === 'string' ? detail : nested?.message)
  const detailsSource = data.details ?? nested?.details ?? (Array.isArray(detail) ? detail : data.errors)
  const details = [...new Set(detailLines(detailsSource))].join('；')
  const fallback = status === 409 ? '数据已被更新或业务状态冲突，请刷新后重试' : status === 403 ? '当前账号无权访问该数据或执行此操作' : status === 401 ? '登录已失效，请重新登录' : '请求失败，请稍后重试'
  return [primary, details].filter((item, index, items) => Boolean(item) && items.indexOf(item) === index).join('：') || fallback
}

http.interceptors.response.use(undefined, (error) => {
  const status = error.response?.status as number | undefined
  error.userMessage = resolveApiErrorMessage(error.response?.data, status)
  if (status === 401 && !String(error.config?.url).includes('/auth/login')) {
    window.dispatchEvent(new CustomEvent('session-expired'))
  } else if (status === 403) {
    ElMessage.error(error.userMessage)
  } else if (status === 409) {
    ElMessage.warning(error.userMessage)
  } else if (status && status >= 500) {
    ElMessage.error(error.userMessage)
  }
  return Promise.reject(error)
})

function unwrap<T>(payload: unknown): T {
  if (payload && typeof payload === 'object' && 'data' in payload) {
    const envelope = payload as Record<string, unknown>
    if ('code' in envelope || 'success' in envelope || 'message' in envelope) return envelope.data as T
  }
  return payload as T
}

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request(config)
  return unwrap<T>(response.data)
}

export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return request<T>({ url, method: 'GET', params })
}
export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'POST', data, ...config })
}
export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'PUT', data, ...config })
}
export function patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'PATCH', data, ...config })
}
export function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'DELETE', ...config })
}

export function normalizeList<T>(payload: ListResult<T> | T[] | { data?: ListResult<T> | T[] }): ListResult<T> {
  const value = ('data' in (payload as object) && !Array.isArray(payload))
    ? (payload as { data?: ListResult<T> | T[] }).data
    : payload
  if (Array.isArray(value)) return { items: value, total: value.length }
  if (value && typeof value === 'object' && 'items' in value && Array.isArray(value.items)) return { items: value.items, total: Number(value.total ?? value.items.length) }
  return { items: [], total: 0 }
}

export async function getList<T>(url: string, params?: PageQuery): Promise<ListResult<T>> {
  const payload = await get<ListResult<T> | T[]>(url, params)
  return normalizeList(payload)
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function commandConfig(key = newIdempotencyKey()): AxiosRequestConfig {
  return { headers: { 'Idempotency-Key': key } }
}

export function pick<T extends object>(data: T, fields: string[]): Partial<T> {
  const result: Record<string, unknown> = {}
  const source = data as Record<string, unknown>
  fields.forEach((field) => { if (source[field] !== undefined) result[field] = source[field] })
  return result as Partial<T>
}

export function withLock<T extends object>(data: T, lockVersion?: number): Partial<T> & { expected_version?: number } {
  const source = { ...data } as Record<string, unknown>
  const current = lockVersion ?? (source.lock_version as number | undefined) ?? (source.expected_version as number | undefined)
  delete source.lock_version
  delete source.expected_version
  return current === undefined ? source as Partial<T> : { ...source, expected_version: current } as Partial<T> & { expected_version: number }
}

export function errorMessage(error: unknown): string {
  if (error && typeof error === 'object' && 'userMessage' in error) return String((error as { userMessage: unknown }).userMessage)
  if (error instanceof Error) return error.message
  return '操作失败，请稍后重试'
}
