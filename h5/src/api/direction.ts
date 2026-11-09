import { http } from '@/utils/request'
import type {
  CreateDirectionRequest,
  Direction,
  UpdateDirectionRequest,
} from '@/types/plan'

export function listDirections(planId: string): Promise<Direction[]> {
  return http.get(`/plans/${planId}/directions`)
}

export function createDirection(planId: string, data: CreateDirectionRequest): Promise<Direction> {
  return http.post(`/plans/${planId}/directions`, { ...data, subjectType: 'SCHOOL' })
}

export function updateDirection(
  directionId: string,
  data: UpdateDirectionRequest,
): Promise<Direction> {
  return http.patch(`/directions/${directionId}`, data)
}

export function deleteDirection(directionId: string): Promise<null> {
  return http.delete(`/directions/${directionId}`)
}
