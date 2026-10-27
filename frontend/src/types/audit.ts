import type { ClientType } from './enums'
import type { UnitRef, UserRef } from './api'

/** 日志动作（API 约定第 9 章） */
export type AuditAction =
  | 'LOGIN'
  | 'CREATE'
  | 'UPDATE'
  | 'DELETE'
  | 'IMPORT'
  | 'EXPORT'
  | 'FINALIZE'
  | 'DISTRIBUTE'
  | 'ACCEPT'
  | 'SUBMIT'
  | 'WITHDRAW'
  | 'REVIEW'
  | 'ADJUST_APPLY'
  | 'ADJUST_DECIDE'
  | 'SETTLE'
  | 'UNLOCK'
  | 'PERMISSION_CHANGE'
  | 'FILE_DOWNLOAD'
  | 'FILE_PREVIEW'

/** GET /audit-logs 列表项（API 约定第 10 章字段；before / after 为 JSON 字符串） */
export interface AuditLogItem {
  id: string
  occurredAt: string
  operator: UserRef
  /** 操作时的角色，英文逗号分隔 */
  roles: string | null
  unit: UnitRef | null
  action: AuditAction | string
  resourceType: string | null
  resourceId: string | null
  resourceCode: string | null
  before: string | null
  after: string | null
  reason: string | null
  clientType: ClientType | null
  ip: string | null
  requestId: string
}

/** GET /audit-logs 查询参数（API 约定 15.9 / 16.6） */
export type AuditLogQuery = {
  occurredAtFrom?: string
  occurredAtTo?: string
  unitId?: string
  /** 多选动作，英文逗号拼接 */
  action?: string
  operatorId?: string
  /** 指标筛选：resourceType=indicator + resourceId=指标 id */
  resourceType?: string
  resourceId?: string
  page?: number
  pageSize?: number
}
