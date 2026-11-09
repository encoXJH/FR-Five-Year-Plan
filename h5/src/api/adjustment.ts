import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  AdjustmentDecisionRequest,
  AdjustmentDetail,
  AdjustmentListItem,
  AdjustmentPreviewRequest,
  AdjustmentPreviewResult,
  AdjustmentQuery,
  CreateAdjustmentRequest,
} from '@/types/adjustment'

/** 试算调整前后的数据和守恒校验结果（不保存，API 15.6） */
export function previewAdjustment(
  data: AdjustmentPreviewRequest,
): Promise<AdjustmentPreviewResult> {
  return http.post('/adjustments/preview', data)
}

/** 发起调整申请（幂等，API 15.6 ⚡） */
export function createAdjustment(
  data: CreateAdjustmentRequest,
  idempotencyKey: string,
): Promise<AdjustmentDetail> {
  return http.post('/adjustments', data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

/** 申请列表：scope=MINE 我发起的 / TODO 待我处理 / ALL 全部（按数据范围） */
export function listAdjustments(
  query: AdjustmentQuery = {},
): Promise<PageResult<AdjustmentListItem>> {
  return http.get('/adjustments', { ...query })
}

/** 详情（含审批环节、调整前后快照） */
export function getAdjustment(adjustmentId: string): Promise<AdjustmentDetail> {
  return http.get(`/adjustments/${adjustmentId}`)
}

/** 当前环节的处理（幂等；服务端自动判断当前环节和处理人，70005 非当前处理人） */
export function decideAdjustment(
  adjustmentId: string,
  data: AdjustmentDecisionRequest,
  idempotencyKey: string,
): Promise<AdjustmentDetail> {
  return http.post(`/adjustments/${adjustmentId}/decision`, data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

/** 申请人撤回（幂等，生效前才可以） */
export function withdrawAdjustment(
  adjustmentId: string,
  version: number,
  idempotencyKey: string,
): Promise<AdjustmentDetail> {
  return http.post(
    `/adjustments/${adjustmentId}/withdraw`,
    { version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

/** 某 B 任务的调整历史时间线 */
export function listTaskAdjustments(taskId: string): Promise<AdjustmentListItem[]> {
  return http.get(`/tasks/${taskId}/adjustments`)
}
