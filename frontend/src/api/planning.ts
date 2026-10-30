import { commandConfig, del, get, getList, patch, pick, post, withLock } from './client'
import type { AnnualTarget, ConstructionPlan, DictionaryItem, Direction, Directory, ID, ImportBatch, ImportRow, Indicator, ListResult, PageQuery } from '@/types/api'

const indicatorCreateFields = ['direction_id', 'indicator_code', 'indicator_name', 'indicator_desc', 'indicator_type_code', 'unit_code', 'target_direction', 'calc_method', 'statistical_rule', 'professional_five_year_target_value', 'professional_five_year_target_bool', 'professional_five_year_target_text', 'professional_five_year_target_raw', 'school_five_year_target_value', 'school_five_year_target_bool', 'school_five_year_target_text', 'school_five_year_target_raw', 'baseline_value', 'baseline_year', 'numerator_rule', 'denominator_rule', 'decimal_precision', 'external_data_source']
const indicatorUpdateFields = ['direction_id', 'indicator_name', 'indicator_desc', 'indicator_type_code', 'unit_code', 'standard_unit_code', 'target_direction', 'calc_method', 'statistical_rule', 'professional_five_year_target_value', 'professional_five_year_target_bool', 'professional_five_year_target_text', 'professional_five_year_target_raw', 'school_five_year_target_value', 'school_five_year_target_bool', 'school_five_year_target_text', 'school_five_year_target_raw', 'baseline_value', 'baseline_year', 'numerator_rule', 'denominator_rule', 'decimal_precision', 'external_data_source', 'parse_status']
const annualFields = ['target_year', 'professional_target_value', 'professional_target_bool', 'professional_target_text', 'professional_target_raw', 'school_target_value', 'school_target_bool', 'school_target_text', 'school_target_raw', 'school_division_raw', 'confirm_status']
function annualPayload(item: Partial<AnnualTarget>) { return withLock(pick(item, annualFields), item.lock_version) }
function indicatorPayload(data: Partial<Indicator>, fields: string[]) {
  const payload: Record<string, unknown> = { ...pick(data, fields) }
  if (data.annual_targets) payload.annual_targets = data.annual_targets.map(annualPayload)
  if (data.departments) payload.departments = data.departments.map((item) => pick(item, ['org_id', 'relation_type', 'responsibility_desc']))
  return payload
}
export function createIndicatorPayload(data: Partial<Indicator>) { return indicatorPayload(data, indicatorCreateFields) }
export function updateIndicatorPayload(data: Partial<Indicator>) { return indicatorPayload(data, indicatorUpdateFields) }
type ImportResponse = ImportBatch & { items?: ImportRow[]; fields?: Record<string, string>; validation_summary?: Record<string, number>; sheets?: string[] }
function importPayload(data: ImportResponse): ImportBatch & { rows?: ImportRow[]; field_mapping?: Record<string, string>; sheets?: string[] } {
  return { ...data, rows: data.items || [], field_mapping: data.fields, ready_count: data.ready_count ?? data.validation_summary?.READY ?? data.validation_summary?.PARSED, confirm_count: data.confirm_count ?? data.validation_summary?.NEED_CONFIRM ?? data.validation_summary?.PENDING, invalid_count: data.invalid_count ?? data.validation_summary?.INVALID ?? data.validation_summary?.FAILED, duplicate_count: data.duplicate_count ?? data.items?.filter((item) => item.is_duplicate).length }
}
async function readImportBatch(id: ID) {
  const data = await get<ImportResponse>(`/imports/${id}`)
  return importPayload(data)
}

export const planningApi = {
  directories: (params?: PageQuery) => getList<Directory>('/directories', params),
  directory: (id: ID) => get<Directory>(`/directories/${id}`),
  createDirectory: (data: Partial<Directory>) => post<Directory>('/directories', pick(data, ['directory_code', 'directory_name', 'description', 'leader_user_id', 'status', 'sort_no']), commandConfig()),
  updateDirectory: (id: ID, data: Partial<Directory>) => patch<Directory>(`/directories/${id}`, withLock(pick(data, ['directory_name', 'description', 'leader_user_id', 'status', 'sort_no']), data.lock_version), commandConfig()),
  deleteDirectory: (id: ID, lock_version?: number, reason?: string) => del<void>(`/directories/${id}`, { data: withLock({ confirm: true, reason }, lock_version), ...commandConfig() }),
  plans: (params?: PageQuery) => getList<ConstructionPlan>('/plans', params),
  plan: (id: ID) => get<ConstructionPlan>(`/plans/${id}`),
  createPlan: (data: Partial<ConstructionPlan>) => post<ConstructionPlan>('/plans', pick(data, ['plan_code', 'directory_id', 'plan_name', 'construction_scope', 'start_year', 'end_year', 'school_owner_id']), commandConfig()),
  copyPlan: (id: ID, data: Partial<ConstructionPlan>) => post<ConstructionPlan>('/plans/copy', { ...pick(data, ['plan_code', 'directory_id', 'plan_name', 'construction_scope', 'start_year', 'end_year', 'school_owner_id', 'copy_mode']), source_plan_id: id }, commandConfig()),
  updatePlan: (id: ID, data: Partial<ConstructionPlan>) => patch<ConstructionPlan>(`/plans/${id}`, withLock(pick(data, ['plan_name', 'construction_scope', 'start_year', 'end_year']), data.lock_version), commandConfig()),
  deletePlan: (id: ID, lock_version?: number, reason?: string) => del<void>(`/plans/${id}`, { data: withLock({ confirm: true, reason }, lock_version), ...commandConfig() }),
  publishPlan: (id: ID, lock_version?: number) => post<ConstructionPlan>(`/plans/${id}/publish`, withLock({ confirm: true }, lock_version), commandConfig()),
  startPlan: (id: ID, lock_version?: number) => post<ConstructionPlan>(`/plans/${id}/start`, withLock({ confirm: true }, lock_version), commandConfig()),
  closePlan: (id: ID, lock_version?: number, reason?: string) => post<ConstructionPlan>(`/plans/${id}/close`, withLock({ confirm: true, reason }, lock_version), commandConfig()),
  directions: (params?: PageQuery) => getList<Direction>('/directions', params),
  createDirection: (data: Partial<Direction>) => post<Direction>('/directions', pick(data, ['plan_id', 'direction_code', 'direction_name', 'source_seq', 'description', 'sort_no']), commandConfig()),
  updateDirection: (id: ID, data: Partial<Direction>) => patch<Direction>(`/directions/${id}`, withLock(pick(data, ['direction_name', 'description', 'sort_no', 'status']), data.lock_version), commandConfig()),
  indicators: (params?: PageQuery) => getList<Indicator>('/indicators', params),
  indicator: (id: ID) => get<Indicator>(`/indicators/${id}`),
  createIndicator: (data: Partial<Indicator>) => {
    const payload = createIndicatorPayload(data)
    return post<Indicator>('/indicators', { ...payload, annual_targets: payload.annual_targets || [], departments: payload.departments || [] }, commandConfig())
  },
  updateIndicator: (id: ID, data: Partial<Indicator>) => patch<Indicator>(`/indicators/${id}`, withLock(updateIndicatorPayload(data), data.lock_version), commandConfig()),
  saveAnnualTargets: (id: ID, annual_targets: Partial<AnnualTarget>[], lock_version?: number) => patch<Indicator>(`/indicators/${id}`, withLock({ annual_targets: annual_targets.map(annualPayload) }, lock_version), commandConfig()),
  publishIndicator: (id: ID, lock_version?: number) => post<Indicator>(`/indicators/${id}/publish`, withLock({ confirm: true }, lock_version), commandConfig()),
  validateIndicator: async (id: ID) => {
    const item = await planningApi.indicator(id)
    const errors: string[] = []
    const warnings: string[] = []
    if (!item.indicator_name || !item.indicator_desc) errors.push('指标名称与完整描述不能为空')
    if (!item.calc_method) errors.push('请选择七类核算方式')
    if (!item.statistical_rule && !['BOOLEAN', 'EXTERNAL'].includes(item.calc_method || '')) errors.push('统计口径待补充')
    if (item.calc_method === 'RATIO' && (!item.numerator_rule || !item.denominator_rule)) errors.push('比率型须填写分子与分母来源口径')
    if (item.calc_method === 'GROWTH' && (item.baseline_value === null || item.baseline_value === undefined || !item.baseline_year)) errors.push('增长率型须填写基期绝对值与基期年度')
    if (item.calc_method === 'EXTERNAL' && !item.external_data_source) errors.push('外部数据型须填写外部数据来源')
    const pendingYears = item.annual_targets?.filter((target) => target.confirm_status !== 'CONFIRMED').map((target) => target.target_year) || []
    if (pendingYears.length) errors.push(`${pendingYears.join('、')} 年目标未确认，请在“02 目标量化”点击“保存并确认量化”`)
    return { valid: errors.length === 0, errors, warnings }
  },
  indicatorTypes: () => getList<DictionaryItem>('/dictionaries/indicator-types', { page_size: 100 }),
  indicatorUnits: () => getList<DictionaryItem>('/dictionaries/indicator-units', { page_size: 100 }),
  imports: async (params?: PageQuery): Promise<ListResult<ImportBatch>> => {
    const result = await getList<ImportResponse>('/imports', params)
    return { items: result.items.map(importPayload), total: result.total }
  },
  importBatch: readImportBatch,
  uploadImport: async (plan_id: ID, file: File, duplicate_mode: 'SKIP' | 'RECREATE' = 'SKIP') => {
    const data = new FormData()
    data.append('plan_id', String(plan_id))
    data.append('duplicate_mode', duplicate_mode)
    data.append('file', file)
    const batch = await post<ImportBatch>('/imports/preview', data, { headers: { 'Idempotency-Key': crypto.randomUUID?.() || `${Date.now()}-${file.name}` }, timeout: 120000 })
    return batch
  },
  importRows: async (id: ID, params?: PageQuery): Promise<ListResult<ImportRow>> => {
    const batch = await readImportBatch(id)
    let items = batch.rows || []
    if (params?.validation_status) items = items.filter((item) => (item.validation_status || item.parse_status) === params.validation_status)
    if (params?.keyword) { const keyword = String(params.keyword).toLowerCase(); items = items.filter((item) => `${item.source_seq_raw || ''}${item.indicator_raw || ''}`.toLowerCase().includes(keyword)) }
    const page = Number(params?.page || 1)
    const size = Number(params?.page_size || 30)
    return { items: items.slice((page - 1) * size, page * size), total: items.length }
  },
  confirmImportRow: (_batchId: ID, rowId: ID, data: { confirmation?: Record<string, unknown>; duplicate_action?: string; lock_version?: number }) => post<ImportRow>(`/imports/rows/${rowId}/confirm`, { confirm: true, regenerate: data.duplicate_action === 'RECREATE', overrides: data.confirmation }, commandConfig()),
  commitImport: (id: ID, duplicate_action: 'SKIP' | 'RECREATE' = 'SKIP') => post<ImportBatch>(`/imports/${id}/confirm-all`, { confirm: true, regenerate: duplicate_action === 'RECREATE', overrides: {} }, commandConfig()),
}
