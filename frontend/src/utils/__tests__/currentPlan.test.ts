import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { clearCurrentPlan, getCurrentPlan, setCurrentPlan } from '../currentPlan'

/** vitest 默认 node 环境没有 localStorage，注入最小 mock */
beforeAll(() => {
  const store = new Map<string, string>()
  const localStorageMock = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
    clear: () => store.clear(),
  }
  ;(globalThis as Record<string, unknown>).localStorage = localStorageMock
  ;(globalThis as Record<string, unknown>).__mockStore = store
})

beforeEach(() => {
  ;((globalThis as Record<string, unknown>).__mockStore as Map<string, string>).clear()
})

describe('currentPlan 上下文', () => {
  it('未设置时返回 null', () => {
    expect(getCurrentPlan()).toBeNull()
  })

  it('setCurrentPlan 后 getCurrentPlan 返回对应上下文', () => {
    setCurrentPlan({ id: '1', name: '规划' })
    expect(getCurrentPlan()).toEqual({ id: '1', name: '规划' })
  })

  it('后设置的计划覆盖先前计划', () => {
    setCurrentPlan({ id: '1', name: '规划 A' })
    setCurrentPlan({ id: '2', name: '规划 B' })
    expect(getCurrentPlan()).toEqual({ id: '2', name: '规划 B' })
  })

  it('clearCurrentPlan 清空上下文', () => {
    setCurrentPlan({ id: '1', name: '规划' })
    clearCurrentPlan()
    expect(getCurrentPlan()).toBeNull()
  })

  it('localStorage 内容损坏时返回 null', () => {
    ;(globalThis as Record<string, unknown>).localStorage = {
      getItem: () => '{broken json',
      setItem: () => undefined,
      removeItem: () => undefined,
      clear: () => undefined,
    }
    expect(getCurrentPlan()).toBeNull()
  })

  it('localStorage 不可用时静默降级', () => {
    ;(globalThis as Record<string, unknown>).localStorage = {
      getItem: () => { throw new Error('denied') },
      setItem: () => { throw new Error('denied') },
      removeItem: () => { throw new Error('denied') },
      clear: () => undefined,
    }
    expect(getCurrentPlan()).toBeNull()
    expect(() => setCurrentPlan({ id: '1', name: '规划' })).not.toThrow()
  })
})
