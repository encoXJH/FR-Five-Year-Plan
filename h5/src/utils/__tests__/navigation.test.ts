import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { resolveBackTarget, resolveSafeInternalRedirect } from '../navigation'

const routes = [
  ['Plans', '/plans'], ['PlanOverview', '/plans/:planId'], ['PlanIndicators', '/plans/:planId/indicators'],
  ['Indicators', '/indicators'], ['IndicatorProgress', '/plans/:planId/indicator-progress'],
  ['PlanAllocation', '/plans/:planId/allocation'], ['Allocation', '/allocation'], ['Templates', '/templates'],
  ['Workbench', '/workbench'], ['TaskSubmit', '/tasks/:taskId/submit'], ['Adjustments', '/adjustments'],
  ['AdjustmentApply', '/adjustments/apply'], ['IndicatorDetail', '/indicators/:id'], ['Stats', '/stats'],
  ['Materials', '/materials'], ['Reviews', '/reviews'], ['AuditLogs', '/logs'], ['Todos', '/todos'],
  ['System', '/system'], ['Screen', '/screen/:planId?'], ['Login', '/login'],
  ['FeishuCallback', '/login/feishu-callback'], ['Forbidden', '/403'],
] as const
const router = createRouter({ history: createMemoryHistory(), routes: routes.map(([name, path]) => ({ name, path, component: {} })) })

function target(url: string, homePath = '/plans', fallback?: string) {
  return resolveBackTarget(router.resolve(url) as RouteLocationNormalizedLoaded, router, homePath, fallback)
}

describe('全站返回导航', () => {
  it('一级页面始终返回角色首页并忽略from', () => {
    expect(target('/stats?from=/plans').href).toBe('/plans')
    expect(target('/workbench?from=/plans', '/workbench')).toEqual({ href: '/workbench', label: '返回首页' })
  })

  it('二级页面返回明确父级并保留planId', () => {
    expect(target('/plans/123/indicator-progress').href).toBe('/plans/123/indicators')
    expect(target('/plans/123/allocation').href).toBe('/plans/123/indicators')
    expect(target('/templates').href).toBe('/plans')
    expect(target('/tasks/456/submit').href).toBe('/workbench')
    expect(target('/adjustments/apply').href).toBe('/adjustments')
  })

  it('指标详情保留合法统计来源及筛选', () => {
    const result = target('/indicators/123?from=/stats?planId=456%26year=2026')
    expect(result).toEqual({ href: '/stats?planId=456&year=2026', label: '返回统计报表' })
  })

  it('指标详情无来源使用动态父级', () => {
    expect(target('/indicators/123', '/plans', '/plans/456/indicators').href).toBe('/plans/456/indicators')
    expect(target('/indicators/123', '/workbench').href).toBe('/workbench')
  })

  it.each([
    '/indicators/123?from=https://example.com',
    '/indicators/123?from=//example.com',
    '/indicators/123?from=/login',
    '/indicators/123?from=/403',
    '/indicators/123?from=/system',
    '/indicators/123?from=/indicators/123',
    '/indicators/123?from=/stats?planId=abc',
  ])('非法或不适用的来源安全回退：%s', (url) => {
    expect(target(url, '/plans', '/plans/456/indicators').href).toBe('/plans/456/indicators')
  })

  it('数组from和当前路由自引用均回退', () => {
    expect(target('/indicators/123?from=/stats&from=/plans', '/plans', '/plans/456/indicators').href).toBe('/plans/456/indicators')
    expect(target('/tasks/123/submit?from=/tasks/123/submit').href).toBe('/workbench')
  })
})

describe('飞书登录安全业务回跳', () => {
  it('保留任务、审核和调整的完整站内地址', () => {
    expect(resolveSafeInternalRedirect(router, '/tasks/123/submit?year=2026')).toBe('/tasks/123/submit?year=2026')
    expect(resolveSafeInternalRedirect(router, '/reviews?reviewNode=LEADER_REVIEW&planId=123'))
      .toBe('/reviews?reviewNode=LEADER_REVIEW&planId=123')
    expect(resolveSafeInternalRedirect(router, '/adjustments?scope=TODO&adjustmentId=456'))
      .toBe('/adjustments?scope=TODO&adjustmentId=456')
  })

  it.each([
    'https://example.com/tasks/123/submit', '//example.com/reviews', '/tasks\\123/submit',
    '/login', '/login/feishu-callback', '/403', '/tasks/not-a-number/submit',
    '/reviews?planId=abc', '/unknown',
  ])('拒绝外部、异常或自循环地址：%s', (value) => {
    expect(resolveSafeInternalRedirect(router, value)).toBeNull()
  })

  it('数组 redirect 和非字符串值均拒绝', () => {
    expect(resolveSafeInternalRedirect(router, ['/plans', '/reviews'])).toBeNull()
    expect(resolveSafeInternalRedirect(router, null)).toBeNull()
  })
})
