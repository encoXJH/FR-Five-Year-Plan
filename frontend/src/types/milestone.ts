import type { MilestoneStatus } from './enums'

/** 里程碑（PRD FR-M7-01：名称、计划完成时间、交付物要求、关联年度目标） */
export interface Milestone {
  id: string
  /** 关联指标（A / A+），与 taskId 二选一 */
  indicatorId?: string | null
  /** 关联 B 任务 */
  taskId?: string | null
  name: string
  /** 关联的年度目标 */
  year?: number | null
  /** 计划完成时间（YYYY-MM-DD 或 ISO 8601） */
  dueDate: string | null
  /** 交付物要求 */
  deliverable?: string | null
  status: MilestoneStatus
  version: number
  createdAt: string
  updatedAt?: string
}

/** GET /milestones 查询参数（API 15.7：按 indicatorId 或 taskId） */
export interface MilestoneQuery {
  indicatorId?: string
  taskId?: string
}

/** POST /milestones 请求体 */
export interface CreateMilestoneRequest {
  indicatorId?: string
  taskId?: string
  name: string
  year?: number | null
  dueDate?: string | null
  deliverable?: string | null
  status?: MilestoneStatus
}

/** PATCH /milestones/{id} 请求体（不传表示不修改） */
export interface UpdateMilestoneRequest {
  name?: string
  year?: number | null
  dueDate?: string | null
  deliverable?: string | null
  status?: MilestoneStatus
  version: number
}
