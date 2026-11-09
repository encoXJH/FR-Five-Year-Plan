import { http } from '@/utils/request'
import type { UnitRef } from '@/types/api'

/** 执行单元（API 15.10；是否可承接新 B 由系统管理配置） */
export interface OrgUnit extends UnitRef {
  type?: string | null
  isEnabled?: boolean
  isBAssignable?: boolean
}

export function listOrgUnits(): Promise<OrgUnit[]> {
  return http.get('/org-units')
}
