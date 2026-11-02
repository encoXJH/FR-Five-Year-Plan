import { http } from '@/utils/request'
import type { PageResult, UnitRef, UserRef } from '@/types/api'
import type { FileCategory } from '@/types/enums'

export interface MaterialIndicator {
  id: string
  code: string
  name: string
}

export interface MaterialIndicatorOption extends MaterialIndicator {
  directionName: string | null
}

export interface MaterialItem {
  id: string
  fileName: string
  size: number
  mimeType: string | null
  category: FileCategory
  bizType: 'INDICATOR' | 'SUBMISSION' | 'ADJUSTMENT' | 'MILESTONE'
  uploadedAt: string
  indicator: MaterialIndicator
  unit: UnitRef | null
  uploadedBy: UserRef | null
  transcodeStatus: 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | null
}

export interface MaterialQuery {
  indicatorId?: string
  unitId?: string
  category?: string
  keyword?: string
  page?: number
  pageSize?: number
}

export function listMaterialIndicators(): Promise<MaterialIndicatorOption[]> {
  return http.get('/files/materials/indicators')
}

export function listMaterials(params: MaterialQuery): Promise<PageResult<MaterialItem>> {
  return http.get('/files/materials', { ...params })
}

export function bindMaterials(indicatorId: string, fileIds: string[]): Promise<void> {
  return http.post('/files/materials/bind', { indicatorId, fileIds })
}
