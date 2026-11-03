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
  /** 填报材料所属年度；非填报材料为 null */
  year: number | null
  /** 填报材料所属 B 任务编号；非填报材料为 null */
  taskCode: string | null
  indicator: MaterialIndicator
  unit: UnitRef | null
  uploadedBy: UserRef | null
  transcodeStatus: 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | null
}

export interface MaterialQuery {
  planId?: string
  indicatorId?: string
  unitId?: string
  /** 精确到 B 任务；传入时只查该任务的 SUBMISSION 材料 */
  taskId?: string
  /** 精确到年度；传入时只查该年度提交的材料 */
  year?: number
  category?: string
  keyword?: string
  page?: number
  pageSize?: number
}

export function listMaterialIndicators(planId?: string): Promise<MaterialIndicatorOption[]> {
  return http.get('/files/materials/indicators', { planId })
}

export function listMaterials(params: MaterialQuery): Promise<PageResult<MaterialItem>> {
  return http.get('/files/materials', { ...params })
}

/** 按关键产出指标统计材料数量（指标列表内联展示） */
export interface IndicatorMaterialCount {
  indicatorId: string
  materialCount: number
}

export function countMaterialsByIndicator(planId?: string): Promise<IndicatorMaterialCount[]> {
  return http.get('/files/materials/counts', { planId })
}

export function bindMaterials(indicatorId: string, fileIds: string[]): Promise<void> {
  return http.post('/files/materials/bind', { indicatorId, fileIds })
}
