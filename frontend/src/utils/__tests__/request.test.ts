import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import service, { http } from '../request'

const originalCrypto = globalThis.crypto
const adapter = vi.fn(async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => ({
  data: { code: 0, message: 'success', data: null, requestId: 'server-generated' },
  status: 200,
  statusText: 'OK',
  headers: {},
  config,
}))

beforeEach(() => {
  const store = new Map<string, string>()
  ;(globalThis as Record<string, unknown>).sessionStorage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
    clear: () => store.clear(),
  }
  adapter.mockClear()
  service.defaults.adapter = adapter
})

afterEach(() => {
  Object.defineProperty(globalThis, 'crypto', { value: originalCrypto, configurable: true })
})

describe('request 请求编号兼容', () => {
  it('randomUUID 不可用时仍发送请求并生成兼容请求编号', async () => {
    Object.defineProperty(globalThis, 'crypto', { value: {}, configurable: true })

    await http.post('/auth/login', { username: 'debug', password: 'invalid' })

    expect(adapter).toHaveBeenCalledOnce()
    const config = adapter.mock.calls[0][0]
    expect(config.headers['X-Request-Id']).toMatch(/^[0-9a-f-]{36}$/)
    expect(config.headers['X-Client-Type']).toBe('WEB')
  })

  it('randomUUID 可用时继续透传客户端请求编号', async () => {
    Object.defineProperty(globalThis, 'crypto', {
      value: { randomUUID: () => 'client-request-id' },
      configurable: true,
    })

    await http.post('/auth/login', { username: 'debug', password: 'invalid' })

    expect(adapter).toHaveBeenCalledOnce()
    expect(adapter.mock.calls[0][0].headers['X-Request-Id']).toBe('client-request-id')
  })
})
