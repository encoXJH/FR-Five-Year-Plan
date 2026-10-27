import { describe, expect, it } from 'vitest'
import { newIdempotencyKey } from '../idempotency'

describe('newIdempotencyKey', () => {
  it('返回 UUID 格式字符串', () => {
    expect(newIdempotencyKey()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    )
  })

  it('每次生成互不相同', () => {
    const keys = new Set(Array.from({ length: 100 }, () => newIdempotencyKey()))
    expect(keys.size).toBe(100)
  })
})
