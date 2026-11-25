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
  const store = (globalThis as Record<string, unknown>).__mockStore as Map<string, string>
  store.clear()
  ;(globalThis as Record<string, unknown>).localStorage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
    clear: () => store.clear(),
  }
})

describe('currentPlan 上下文', () => {
  it('未设置时返回 null', () => {
    expect(getCurrentPlan()).toBeNull()
  })

  it('setCurrentPlan 后 getCurrentPlan 返回对应上下文', () => {
    setCurrentPlan({ id: '1', name: '规划' })
    expect(getCurrentPlan()).toEqual({ id: '1', name: '规划', planType: 'INDICATOR' })
  })

  it('后设置的计划覆盖先前计划', () => {
    setCurrentPlan({ id: '1', name: '规划 A' })
    setCurrentPlan({ id: '2', name: '规划 B' })
    expect(getCurrentPlan()).toEqual({ id: '2', name: '规划 B', planType: 'INDICATOR' })
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

  it('培训计划不能写入指标当前项目并会清除旧选择', () => {
    setCurrentPlan({ id: '1', name: '指标计划', planType: 'INDICATOR' })
    setCurrentPlan({ id: '2', name: '培训计划', planType: 'TRAINING' })

    expect(getCurrentPlan()).toBeNull()
  })

  it('没有计划类型的历史缓存按无效处理', () => {
    localStorage.setItem('metrics:current-plan', JSON.stringify({ id: '2', name: '历史培训缓存' }))

    expect(getCurrentPlan()).toBeNull()
  })
})
