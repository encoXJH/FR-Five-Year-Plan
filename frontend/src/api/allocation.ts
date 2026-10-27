import { http } from '@/utils/request'
import type {
  Allocation,
  DecompositionRequest,
  DecompositionResult,
  DistributeRequest,
  DistributeResult,
  SaveAllocationRequest,
} from '@/types/allocation'

export function getAllocation(indicatorId: string): Promise<Allocation> {
  return http.get(`/indicators/${indicatorId}/allocation`)
}

export function saveAllocation(
  indicatorId: string,
  data: SaveAllocationRequest,
  silent = false,
): Promise<Allocation> {
  return http.put(`/indicators/${indicatorId}/allocation`, data, { silent })
}

export function decomposeIndicator(
  planId: string,
  parentId: string,
  data: DecompositionRequest,
  idempotencyKey: string,
): Promise<DecompositionResult> {
  return http.post(`/plans/${planId}/indicators/${parentId}/decomposition`, data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function distributePlan(
  planId: string,
  data: DistributeRequest,
  idempotencyKey: string,
): Promise<DistributeResult> {
  return http.post(`/plans/${planId}/distribute`, data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}
