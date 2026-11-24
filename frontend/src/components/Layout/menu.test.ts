import { beforeAll, describe, expect, it } from 'vitest'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { menuGroups } from './menu'

const overviewItem = menuGroups
  .find((group) => group.key === 'workbench')
  ?.items.find((item) => item.path === 'plan-overview')

beforeAll(() => {
  ;(globalThis as Record<string, unknown>).localStorage = {
    getItem: () => null,
    setItem: () => undefined,
    removeItem: () => undefined,
  }
})

describe('计划概览菜单入口', () => {
  it('重新登录没有路由或缓存计划时仍显示，并进入通用概览入口', () => {
    const route = { params: {} } as RouteLocationNormalizedLoaded

    expect(overviewItem).toBeDefined()
    expect(overviewItem?.visibleWhen?.(route) ?? true).toBe(true)
    expect(overviewItem?.resolvePath?.(route)).toBe('/plan-overview')
  })

  it('从计划卡进入职业培训预览时仍高亮计划概览', () => {
    const route = {
      name: 'TrainingSheetDetail',
      params: { sheetId: '456' },
      query: { from: 'plans' },
    } as unknown as RouteLocationNormalizedLoaded

    expect(overviewItem?.activeWhen?.(route)).toBe(true)
  })

  it('已有路由计划时直接进入对应概览', () => {
    const route = { params: { planId: '123' } } as unknown as RouteLocationNormalizedLoaded

    expect(overviewItem?.resolvePath?.(route)).toBe('/plans/123')
  })
})
