import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'

const PRIMARY_ROUTES = new Set<string>([
  'Plans', 'PlanOverview', 'PlanIndicators', 'Indicators', 'Workbench', 'Materials',
  'Reviews', 'Adjustments', 'Stats', 'AuditLogs', 'Todos', 'System', 'Screen',
])

interface BackRule {
  label: string
  allowedFrom?: string[]
  parent: (route: RouteLocationNormalizedLoaded) => string | null
}

const RULES: Record<string, BackRule> = {
  IndicatorProgress: { label: '返回指标总表', parent: (route) => planIndicators(route) },
  PlanAllocation: { label: '返回指标总表', parent: (route) => planIndicators(route) },
  Allocation: { label: '返回指标总表', parent: () => '/indicators' },
  Templates: { label: '返回计划列表', parent: () => '/plans' },
  TaskSubmit: { label: '返回工作台', allowedFrom: ['Workbench', 'PlanIndicators', 'Indicators'], parent: () => '/workbench' },
  AdjustmentApply: { label: '返回调整列表', allowedFrom: ['Adjustments', 'Workbench', 'IndicatorDetail'], parent: () => '/adjustments' },
  IndicatorDetail: { label: '返回指标总表', allowedFrom: ['Stats', 'PlanIndicators', 'Indicators'], parent: () => null },
}

export interface BackTarget {
  href: string
  label: string
}

function planIndicators(route: RouteLocationNormalizedLoaded): string | null {
  const planId = route.params.planId
  return typeof planId === 'string' && /^\d+$/.test(planId) ? `/plans/${planId}/indicators` : null
}

function hasInvalidIds(route: Pick<RouteLocationNormalizedLoaded, 'params' | 'query'>): boolean {
  const pathValues = Object.entries(route.params).filter(([key]) => key.toLowerCase().endsWith('id'))
  if (pathValues.some(([, value]) => typeof value !== 'string' || !/^\d+$/.test(value))) return true
  return Object.entries(route.query).some(([key, value]) =>
    key.toLowerCase().endsWith('id') && (typeof value !== 'string' || !/^\d+$/.test(value)),
  )
}

function validFrom(route: RouteLocationNormalizedLoaded, router: Router, allowed?: string[]): string | null {
  const from = route.query.from
  if (!allowed || typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//') || from.includes('\\')) return null
  const resolved = router.resolve(from)
  if (!resolved.name || !allowed.includes(String(resolved.name))) return null
  if (['Login', 'FeishuCallback', 'Forbidden'].includes(String(resolved.name))) return null
  if (resolved.name === route.name || hasInvalidIds(resolved)) return null
  return resolved.fullPath
}

export function resolveBackTarget(
  route: RouteLocationNormalizedLoaded,
  router: Router,
  homePath: string,
  fallback?: string,
): BackTarget {
  const routeName = route.name ? String(route.name) : ''
  if (PRIMARY_ROUTES.has(routeName)) return { href: homePath, label: '返回首页' }
  const rule = RULES[routeName]
  const from = validFrom(route, router, rule?.allowedFrom)
  if (from) {
    const resolved = router.resolve(from)
    return { href: from, label: resolved.name === 'Stats' ? '返回统计报表' : (rule?.label ?? '返回') }
  }
  return { href: fallback || rule?.parent(route) || homePath, label: rule?.label ?? '返回首页' }
}
