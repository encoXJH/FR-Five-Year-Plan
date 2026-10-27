import { http } from '@/utils/request'
import type {
  CreateMilestoneRequest,
  Milestone,
  MilestoneQuery,
  UpdateMilestoneRequest,
} from '@/types/milestone'

/** 里程碑列表（API 15.7：按 indicatorId 或 taskId 查询） */
export function listMilestones(query: MilestoneQuery): Promise<Milestone[]> {
  return http.get('/milestones', { ...query })
}

export function createMilestone(data: CreateMilestoneRequest): Promise<Milestone> {
  return http.post('/milestones', data)
}

export function updateMilestone(
  milestoneId: string,
  data: UpdateMilestoneRequest,
): Promise<Milestone> {
  return http.patch(`/milestones/${milestoneId}`, data)
}

export function deleteMilestone(milestoneId: string, version: number): Promise<null> {
  return http.delete(`/milestones/${milestoneId}?version=${version}`)
}
