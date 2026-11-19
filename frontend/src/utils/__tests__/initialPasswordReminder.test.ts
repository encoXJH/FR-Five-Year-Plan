import { beforeEach, describe, expect, it } from 'vitest'
import { acknowledgeInitialPasswordReminder, shouldShowInitialPasswordReminder } from '../initialPasswordReminder'

beforeEach(() => {
  const store = new Map<string, string>()
  ;(globalThis as Record<string, unknown>).localStorage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
  }
})

describe('初始密码单次提醒', () => {
  it('非初始密码或没有用户时不提醒', () => {
    expect(shouldShowInitialPasswordReminder('1', false)).toBe(false)
    expect(shouldShowInitialPasswordReminder(null, true)).toBe(false)
  })

  it('知悉前提醒，知悉后不再提醒', () => {
    expect(shouldShowInitialPasswordReminder('1', true)).toBe(true)
    acknowledgeInitialPasswordReminder('1')
    expect(shouldShowInitialPasswordReminder('1', true)).toBe(false)
  })

  it('不同账号分别记录知悉状态', () => {
    acknowledgeInitialPasswordReminder('1')
    expect(shouldShowInitialPasswordReminder('2', true)).toBe(true)
  })

  it('存储不可用时只保证本次提醒且不抛错', () => {
    ;(globalThis as Record<string, unknown>).localStorage = {
      getItem: () => { throw new Error('denied') },
      setItem: () => { throw new Error('denied') },
    }
    expect(shouldShowInitialPasswordReminder('1', true)).toBe(true)
    expect(() => acknowledgeInitialPasswordReminder('1')).not.toThrow()
  })
})
