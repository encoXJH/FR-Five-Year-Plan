import type { PlanStatus, PlanType } from '@/types/enums'

type PermissionChecker = (...codes: string[]) => boolean

export function canExportTrainingPlan(
  planType: PlanType | undefined,
  can: PermissionChecker,
): boolean {
  return planType === 'TRAINING' && can('training:fill', 'submission:review')
}

export function canArchivePlanCard(canManage: boolean, status: PlanStatus): boolean {
  return canManage && status !== 'ARCHIVED'
}

export function canDeletePlanCard(canManage: boolean, status: PlanStatus): boolean {
  return canManage && (status === 'DRAFT' || status === 'ARCHIVED')
}

export function trainingPlanExportFilename(planName: string): string {
  return `${planName.replace(/[\\/:*?"<>|]/g, '_')}-职业培训信息表.xlsx`
}
