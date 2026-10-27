import { http } from '@/utils/request'
import type { CreateExportRequest, CreateExportResult, ExportJob } from '@/types/export'

export function createExport(
  data: CreateExportRequest,
  idempotencyKey: string,
): Promise<CreateExportResult> {
  return http.post('/exports', data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function getExport(exportId: string): Promise<ExportJob> {
  return http.get(`/exports/${exportId}`)
}

/**
 * 轮询导出任务直到完成；onJob 每次轮询回调（用于更新常驻通知）。
 * 超时（默认 2 分钟）抛错。
 */
export async function pollExport(
  exportId: string,
  onJob?: (job: ExportJob) => void,
  options: { intervalMs?: number; timeoutMs?: number } = {},
): Promise<ExportJob> {
  const intervalMs = options.intervalMs ?? 1500
  const timeoutMs = options.timeoutMs ?? 120000
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const job = await getExport(exportId)
    onJob?.(job)
    if (job.status === 'SUCCEEDED' || job.status === 'FAILED') {
      return job
    }
    if (Date.now() > deadline) {
      throw new Error('导出任务超时，请稍后在导出记录中查看')
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }
}
