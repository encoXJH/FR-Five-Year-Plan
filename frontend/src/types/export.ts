import type { JobStatus } from './enums'

export type ExportType = 'INDICATORS' | 'TASKS' | 'STATS_REPORT' | 'SETTLEMENT' | 'AUDIT_LOGS'

/** POST /exports 请求体 */
export interface CreateExportRequest {
  type: ExportType
  /** AUDIT_LOGS 的 action 为数组（API 约定 16.6 示例） */
  params: Record<string, string | number | boolean | string[] | undefined>
}

/** POST /exports 响应 */
export interface CreateExportResult {
  exportId: string
}

/** GET /exports/{exportId} 响应 */
export interface ExportJob {
  id: string
  type: ExportType
  status: JobStatus
  /** 成功时的签名下载地址 */
  downloadUrl: string | null
  fileName: string | null
  errorMessage: string | null
  createdAt: string
  finishedAt: string | null
}
