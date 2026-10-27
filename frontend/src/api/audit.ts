import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { AuditLogItem, AuditLogQuery } from '@/types/audit'

/** GET /audit-logs（API 15.9 / 16.6，权限 audit-log:view） */
export function listAuditLogs(params: AuditLogQuery): Promise<PageResult<AuditLogItem>> {
  return http.get('/audit-logs', params)
}
