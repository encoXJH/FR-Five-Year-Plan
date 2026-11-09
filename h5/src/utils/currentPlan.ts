/** 当前项目上下文：首页选定 / 新建计划后，统计报表与可视化大屏自动跟随该计划 */
const KEY = 'metrics:current-plan'

export interface CurrentPlanContext {
  id: string
  name: string
}

export function getCurrentPlan(): CurrentPlanContext | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as CurrentPlanContext
    return parsed && parsed.id ? parsed : null
  } catch {
    return null
  }
}

export function setCurrentPlan(plan: { id: string; name: string }): void {
  try {
    localStorage.setItem(KEY, JSON.stringify({ id: plan.id, name: plan.name }))
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
