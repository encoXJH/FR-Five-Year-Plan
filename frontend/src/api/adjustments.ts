import { commandConfig, get, getList, pick, post, withLock } from './client'
import type { Carryover, CollegeTask, Compensation, ID, ListResult, PageQuery, Transfer, TransferAttachment } from '@/types/api'

export function transferAttachmentUrl(item: TransferAttachment): string {
  const value = item.preview_url || item.url || item.attachment_url || item.download_url || ''
  if (value.startsWith('/')) return value
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? value : ''
  } catch { return '' }
}

async function taskMap(ids: ID[]) {
  const entries = await Promise.all([...new Set(ids.map(String))].map(async (id) => {
    try { return [id, await get<CollegeTask>(`/tasks/${id}`)] as const } catch { return undefined }
  }))
  return new Map(entries.filter((item): item is readonly [string, CollegeTask] => Boolean(item)))
}
function carryoverPayload(row: Carryover, tasks: Map<string, CollegeTask>): Carryover {
  const task = tasks.get(String(row.task_id))
  return { ...row, task_code: task?.task_code, indicator_name: task?.indicator_name || task?.indicator?.indicator_name, source_year: task?.milestones?.find((item) => item.id === row.source_milestone_id)?.target_year, target_year: task?.milestones?.find((item) => item.id === row.target_milestone_id)?.target_year }
}
async function transferPayload(row: Transfer, tasks: Map<string, CollegeTask>): Promise<Transfer> {
  const task = tasks.get(String(row.source_task_id))
  const organizations = await getList<{ id: ID; org_name: string }>('/organizations').catch(() => ({ items: [], total: 0 }))
  return { ...row, source_task_code: task?.task_code, indicator_name: task?.indicator_name || task?.indicator?.indicator_name, plan_id: task?.plan_id, source_college_org_name: organizations.items.find((item) => item.id === task?.college_org_id)?.org_name, target_college_org_name: organizations.items.find((item) => item.id === row.target_college_org_id)?.org_name }
}

export const adjustmentApi = {
  carryovers: async (params?: PageQuery) => {
    const result = await getList<Carryover>('/carryovers', params)
    const tasks = await taskMap(result.items.map((item) => item.task_id))
    result.items = result.items.map((item) => carryoverPayload(item, tasks)).filter((item) => !params?.carryover_status || item.carryover_status === params.carryover_status)
    result.total = result.items.length
    return result
  },
  carryover: async (id: ID) => {
    const row = await get<Carryover>(`/carryovers/${id}`)
    return carryoverPayload(row, await taskMap([row.task_id]))
  },
  createCarryover: (data: Record<string, unknown>) => post<Carryover>(`/tasks/${data.task_id}/carryovers`, withLock(pick(data, ['source_milestone_id', 'target_milestone_id', 'carryover_amount', 'reason']), data.lock_version as number | undefined), commandConfig()),
  approveCarryover: (id: ID, approved: boolean, comment: string, lock_version?: number) => post<Carryover>(`/carryovers/${id}/action`, withLock({ action: approved ? 'APPROVE' : 'RETURN', reason: comment, confirm: true }, lock_version), commandConfig()),
  withdrawCarryover: (id: ID, reason: string, lock_version?: number) => post<Carryover>(`/carryovers/${id}/action`, withLock({ action: 'WITHDRAW', reason, confirm: true }, lock_version), commandConfig()),
  compensations: async (params?: PageQuery): Promise<ListResult<Compensation>> => {
    let items: Compensation[]
    if (params?.task_id) {
      items = (await getList<Compensation>(`/tasks/${params.task_id}/compensations`)).items
    } else {
      const tasks = await getList<CollegeTask>('/tasks', { page_size: 200 })
      const results = await Promise.all(tasks.items.map((task) => getList<Compensation>(`/tasks/${task.id}/compensations`).catch(() => ({ items: [] as Compensation[], total: 0 }))))
      items = results.flatMap((result) => result.items)
    }
    const details = await taskMap(items.map((item) => item.task_id))
    items = items.map((item) => {
      const task = details.get(String(item.task_id))
      return { ...item, task_code: task?.task_code, indicator_name: task?.indicator_name || task?.indicator?.indicator_name, deficit_year: task?.milestones?.find((milestone) => milestone.id === item.deficit_milestone_id)?.target_year, surplus_year: task?.milestones?.find((milestone) => milestone.id === item.surplus_milestone_id)?.target_year }
    })
    return { items, total: items.length }
  },
  transfers: async (params?: PageQuery) => {
    const result = await getList<Transfer>('/transfers', params)
    const tasks = await taskMap(result.items.map((item) => item.source_task_id))
    result.items = await Promise.all(result.items.map((item) => transferPayload(item, tasks)))
    if (params?.transfer_status) result.items = result.items.filter((item) => item.transfer_status === params.transfer_status)
    if (params?.keyword) { const keyword = String(params.keyword).toLowerCase(); result.items = result.items.filter((item) => `${item.transfer_no}${item.indicator_name || ''}`.toLowerCase().includes(keyword)) }
    result.total = result.items.length
    return result
  },
  transfer: async (id: ID) => {
    const row = await get<Transfer>(`/transfers/${id}`)
    return transferPayload(row, await taskMap([row.source_task_id]))
  },
  createTransfer: (data: Record<string, unknown>) => post<Transfer>(`/indicators/${data.indicator_id}/transfers`, withLock(pick(data, ['source_task_id', 'target_college_org_id', 'target_task_id', 'target_owner_user_id', 'transfer_year', 'transfer_amount', 'reason', 'attachment_json']), data.lock_version as number | undefined), commandConfig()),
  uploadTransferAttachment: async (file: File) => {
    const data = new FormData()
    data.append('file', file)
    const result = await post<TransferAttachment>('/transfers/attachments', data, { headers: { 'Idempotency-Key': crypto.randomUUID?.() || `${Date.now()}-${file.name}` }, timeout: 120000 })
    return { ...result, name: result.name || result.original_file_name || result.file_name || file.name }
  },
  actTransfer: async (id: ID, action: 'CONFIRM' | 'APPROVE' | 'RETURN', comment: string, lock_version?: number) => {
    const current = await get<Transfer>(`/transfers/${id}`)
    const step_code = current.transfer_status === 'PENDING_OUT_CONFIRM' ? 'OUT_COLLEGE' : current.transfer_status === 'PENDING_IN_CONFIRM' ? 'IN_COLLEGE' : 'SCHOOL_LEADER'
    return post<Transfer>(`/transfers/${id}/action`, withLock({ step_code, action: action === 'RETURN' ? 'RETURN' : 'CONFIRM', action_comment: comment, confirm: true }, lock_version), commandConfig())
  },
  withdrawTransfer: (id: ID, reason: string, lock_version?: number) => post<Transfer>(`/transfers/${id}/withdraw`, withLock({ reason }, lock_version), commandConfig()),
  copyTransfer: (id: ID, data: Record<string, unknown> = {}) => post<Transfer>(`/transfers/${id}/copy`, withLock(pick(data, ['transfer_amount', 'transfer_year', 'target_college_org_id', 'target_task_id', 'target_owner_user_id', 'reason']), data.lock_version as number | undefined), commandConfig()),
}
