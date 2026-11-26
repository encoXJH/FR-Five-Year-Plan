import { http } from '@/utils/request'
import type {
  BudgetPutRequest,
  CreateIndicatorRequest,
  ImportConfirmRequest,
  ImportConfirmResult,
  ImportPreviewResult,
  IndicatorBudgetView,
  IndicatorDetail,
  IndicatorQuery,
  IndicatorTreeNode,
  UpdateIndicatorRequest,
  UpdateYearTargetsRequest,
} from '@/types/indicator'

export function listIndicators(
  planId: string,
  query: IndicatorQuery = {},
): Promise<IndicatorTreeNode[]> {
  return http.get(`/plans/${planId}/indicators`, { view: 'tree', ...query })
}

export function createIndicator(
  planId: string,
  data: CreateIndicatorRequest,
): Promise<IndicatorDetail> {
  return http.post(`/plans/${planId}/indicators`, data)
}

export function getIndicator(indicatorId: string): Promise<IndicatorDetail> {
  return http.get(`/indicators/${indicatorId}`)
}

export function updateIndicator(
  indicatorId: string,
  data: UpdateIndicatorRequest,
): Promise<IndicatorDetail> {
  return http.patch(`/indicators/${indicatorId}`, data)
}

export function deleteIndicator(indicatorId: string): Promise<null> {
  return http.delete(`/indicators/${indicatorId}`)
}

/** 仅 ADMIN 在系统管理中设置草稿 A 的 S 归属 */
export function updateIndicatorDirection(
  indicatorId: string,
  directionId: string | null,
  version: number,
): Promise<IndicatorDetail> {
  return http.put(`/indicators/${indicatorId}/direction`, { directionId, version })
}

export function updateYearTargets(
  indicatorId: string,
  data: UpdateYearTargetsRequest,
): Promise<IndicatorDetail> {
  return http.put(`/indicators/${indicatorId}/year-targets`, data)
}

/** 下载 Excel 导入模板（xlsx 文件流） */
export function downloadImportTemplate(planId: string): Promise<Blob> {
  return http.get(`/plans/${planId}/indicators/import-template`, undefined, {
    responseType: 'blob',
  })
}

/** 上传 Excel，返回 importId 与逐行解析结果 */
export function importPreview(planId: string, file: File): Promise<ImportPreviewResult> {
  const form = new FormData()
  form.append('file', file)
  return http.post(`/plans/${planId}/indicators/import-preview`, form)
}

export function importConfirm(
  planId: string,
  data: ImportConfirmRequest,
  idempotencyKey: string,
): Promise<ImportConfirmResult> {
  return http.post(`/plans/${planId}/indicators/import-confirm`, data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

/** 维护 A 级指标总预算（万元）；幂等，totalBudget 传 null 清除预算 */
export function putIndicatorBudget(
  indicatorId: string,
  data: BudgetPutRequest,
  idempotencyKey: string,
): Promise<IndicatorDetail> {
  return http.put(`/indicators/${indicatorId}/budget`, data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

/** 预算概览 + 支出明细（明细按当前用户可见任务过滤） */
export function getIndicatorBudget(
  indicatorId: string,
  query: { page?: number; pageSize?: number; year?: number } = {},
): Promise<IndicatorBudgetView> {
  return http.get(`/indicators/${indicatorId}/budget`, query)
}
