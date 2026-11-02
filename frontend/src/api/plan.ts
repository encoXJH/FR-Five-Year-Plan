import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  CreatePlanRequest,
  PlanCard,
  PlanDetail,
  SaveAsTemplateRequest,
  UpdatePlanRequest,
} from '@/types/plan'
import type { PlanTemplate } from '@/types/plan'

type PlanListItem = Omit<PlanCard, 'overallCompletionRate' | 'yearCompletionRate' | 'updatedAt'> & {
  completionRate: number | null
  createdAt: string
  updatedAt: string | null
}

export async function listPlans(keyword?: string): Promise<PlanCard[]> {
  const data = await http.get<PlanListItem[] | PageResult<PlanListItem>>('/plans', { keyword })
  const items = Array.isArray(data) ? data : data.items
  return items.map((item) => {
    // 已有任务但尚未产生达标数据时按 0% 展示，避免计划卡片只显示“—”
    const completionRate = item.completionRate ?? (item.taskCountB > 0 ? 0 : null)
    return {
      ...item,
      overallCompletionRate: completionRate,
      yearCompletionRate: completionRate,
      updatedAt: item.updatedAt ?? item.createdAt,
    }
  })
}

export function createPlan(data: CreatePlanRequest): Promise<PlanDetail> {
  return http.post('/plans', data)
}

export async function getPlan(planId: string): Promise<PlanDetail> {
  const plan = await http.get<PlanDetail>(`/plans/${planId}`)
  return { ...plan, indicatorsFinalized: plan.indicatorsFinalized ?? plan.finalizedAt != null }
}

export function updatePlan(planId: string, data: UpdatePlanRequest): Promise<PlanDetail> {
  return http.patch(`/plans/${planId}`, data)
}

export function activatePlan(planId: string, version: number): Promise<PlanDetail> {
  return http.post(`/plans/${planId}/activate`, { version })
}

/** 激活计划年度（幂等），激活后学院才能填报当年 */
export function activatePlanYear(
  planId: string,
  year: number,
  version: number,
  idempotencyKey: string,
): Promise<PlanDetail> {
  return http.post(
    `/plans/${planId}/years/${year}/activate`,
    { version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

/** 归档（敏感操作，需带乐观锁 version；reason 用于审计留痕） */
export function archivePlan(planId: string, version: number, reason?: string): Promise<PlanDetail> {
  return http.post(`/plans/${planId}/archive`, { version, reason })
}

export function savePlanAsTemplate(
  planId: string,
  data: SaveAsTemplateRequest,
): Promise<PlanTemplate> {
  return http.post(`/plans/${planId}/save-as-template`, data)
}
