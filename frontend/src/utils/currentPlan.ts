import type { PlanType } from '@/types/enums'

/** 当前项目上下文：仅保存 INDICATOR，统计与材料页面不得跟随培训计划 */
const KEY = 'metrics:current-plan'

export interface CurrentPlanContext {
  id: string
  name: string
  planType: 'INDICATOR'
}

export function getCurrentPlan(): CurrentPlanContext | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as CurrentPlanContext
    return parsed && parsed.id && parsed.planType === 'INDICATOR' ? parsed : null
  } catch {
    return null
  }
}

export function setCurrentPlan(plan: { id: string; name: string; planType?: PlanType }): void {
  if (plan.planType === 'TRAINING') {
    clearCurrentPlan()
    return
  }
  try {
    localStorage.setItem(KEY, JSON.stringify({
      id: plan.id,
      name: plan.name,
      planType: 'INDICATOR',
    }))
  } catch {
    // 存储不可用时静默降级，不影响主流程
  }
}

export function clearCurrentPlan(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // 忽略
  }
}
