import { afterEach, describe, expect, it } from 'vitest'
import { createUuid } from '../uuid'

const originalCrypto = globalThis.crypto
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

afterEach(() => {
  Object.defineProperty(globalThis, 'crypto', { value: originalCrypto, configurable: true })
})

describe('createUuid', () => {
  it('优先使用浏览器原生 randomUUID', () => {
    Object.defineProperty(globalThis, 'crypto', {
      value: { randomUUID: () => 'native-uuid' },
      configurable: true,
    })

    expect(createUuid()).toBe('native-uuid')
  })

  it('randomUUID 不可用时使用 getRandomValues 生成 UUID', () => {
    Object.defineProperty(globalThis, 'crypto', {
      value: { getRandomValues: (bytes: Uint8Array) => bytes.fill(0) },
      configurable: true,
    })

    expect(createUuid()).toBe('00000000-0000-4000-8000-000000000000')
  })

  it('无加密 API 时仍生成合法且不同的 UUID', () => {
    Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true })

    const first = createUuid()
    const second = createUuid()

    expect(first).toMatch(uuidPattern)
    expect(second).toMatch(uuidPattern)
    expect(first).not.toBe(second)
  })
})
