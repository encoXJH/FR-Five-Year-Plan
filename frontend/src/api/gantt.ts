import { http } from '@/utils/request'
import type { PlanGantt } from '@/types/gantt'

export function getPlanGantt(planId: string, indicatorId?: string): Promise<PlanGantt> {
  return http.get(`/plans/${planId}/gantt`, indicatorId ? { indicatorId } : undefined)
}
