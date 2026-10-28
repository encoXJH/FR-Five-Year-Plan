import { http } from '@/utils/request'
import type {
  Allocation,
<<<<<<< HEAD
  DecompositionRequest,
  DecompositionResult,
=======
>>>>>>> fe5ddcd2a9c737aa5a2faaeb19f4cef3e55b9e1d
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

<<<<<<< HEAD
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

=======
>>>>>>> fe5ddcd2a9c737aa5a2faaeb19f4cef3e55b9e1d
export function distributePlan(
  planId: string,
  data: DistributeRequest,
  idempotencyKey: string,
): Promise<DistributeResult> {
  return http.post(`/plans/${planId}/distribute`, data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}
