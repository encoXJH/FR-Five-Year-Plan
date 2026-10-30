import { commandConfig, del, get, getList, normalizeList, patch, pick, post, withLock } from './client'
import type { CollegeTask, EvidenceMaterial, GanttNode, ID, LeaderDashboard, ListResult, Milestone, PageQuery, ReviewRecord, Submission, TodoItem } from '@/types/api'

const submissionFields = ['reported_value', 'reported_bool', 'reported_text', 'reported_numerator', 'reported_denominator', 'completion_desc', 'achievement_name', 'completion_date']
type SubmissionPayload = Submission & { reviews?: ReviewRecord[]; history?: Submission[] }
function submissionPayload(data: SubmissionPayload): SubmissionPayload {
  const milestone = data.milestone
  const task = data.task
  return { ...data, task_id: data.task_id || task?.id, target_year: data.target_year || milestone?.target_year, task_code: data.task_code || task?.task_code, indicator_name: data.indicator_name || data.indicator?.indicator_name }
}
async function readTask(id: ID, enrich = true): Promise<CollegeTask> {
  const data = await get<CollegeTask & { objections?: Array<{ id: ID; objection_text: string; reply_text?: string; objection_status: string; lock_version?: number }> }>(`/tasks/${id}`)
  let names: Partial<CollegeTask> = {}
  if (enrich) {
    const [plan, organizations] = await Promise.all([get<{ plan_name: string }>(`/plans/${data.plan_id}`).catch(() => undefined), getList<{ id: ID; org_name: string }>('/organizations').catch(() => ({ items: [], total: 0 }))])
    names = { plan_name: plan?.plan_name, college_org_name: organizations.items.find((item) => item.id === data.college_org_id)?.org_name }
  }
  const latest = data.objections?.at(-1)
  return { ...data, ...names, statistical_rule: data.statistical_rule || data.indicator?.statistical_rule, unit_name: data.unit_name || data.indicator?.unit_raw || data.unit_code, unit_raw: data.unit_raw || data.indicator?.unit_raw, value_kind: data.value_kind || data.indicator?.value_kind, objection_id: latest?.id, objection_lock_version: latest?.lock_version, objection_content: latest?.objection_text, objection_reply: latest?.reply_text, objection_status: latest?.objection_status, lock_version: data.lock_version }
}
async function readSubmission(id: ID): Promise<SubmissionPayload> {
  const data = await get<SubmissionPayload>(`/submissions/${id}`)
  const planId = data.indicator?.plan_id
  const collegeOrgId = data.task?.college_org_id
  const [plan, organizations] = await Promise.all([
    planId ? get<{ plan_name: string }>(`/plans/${planId}`).catch(() => undefined) : Promise.resolve(undefined),
    collegeOrgId ? getList<{ id: ID; org_name: string }>('/organizations').catch(() => ({ items: [], total: 0 })) : Promise.resolve({ items: [], total: 0 }),
  ])
  const planName = plan?.plan_name
  const collegeOrgName = organizations.items.find((item) => item.id === collegeOrgId)?.org_name
  return submissionPayload({ ...data, plan_name: data.plan_name || planName, college_org_name: data.college_org_name || collegeOrgName, task: data.task ? { ...data.task, plan_name: data.task.plan_name || planName, college_org_name: data.task.college_org_name || collegeOrgName } : data.task })
}
function milestonePayload(item: Milestone & { progress_percent?: number | string }): Milestone {
  return { ...item, completion_rate: item.completion_rate ?? item.progress_percent }
}
function ganttNodes(items: Array<Record<string, unknown>>): GanttNode[] {
  return items.map((direction) => ({
    id: direction.id as ID, node_key: `DIRECTION-${direction.id}`, node_type: 'DIRECTION', name: String(direction.direction_name || ''), code: direction.direction_code as string, status_counts: direction.status_counts as Record<string, number>,
    children: ((direction.indicators || []) as Array<Record<string, unknown>>).map((indicator) => ({
      id: indicator.id as ID, node_key: `INDICATOR-${indicator.id}`, node_type: 'INDICATOR', name: String(indicator.indicator_name || ''), code: indicator.indicator_code as string, direction_id: direction.id as ID, plan_id: indicator.plan_id as ID, indicator_type_code: indicator.indicator_type_code as string, unit_name: indicator.unit_raw as string, calc_method: indicator.calc_method as GanttNode['calc_method'], status_counts: indicator.status_counts as Record<string, number>,
      children: ((indicator.tasks || []) as Array<Record<string, unknown>>).map((task) => ({ id: task.id as ID, node_key: `TASK-${task.id}`, node_type: 'TASK', name: `学院任务｜${String(task.college_org_name || task.task_code || `任务 #${task.id}`)}`, code: task.task_code as string, task_id: task.id as ID, indicator_id: indicator.id as ID, direction_id: direction.id as ID, plan_id: indicator.plan_id as ID, college_org_id: task.college_org_id as ID, owner_user_id: task.owner_user_id as ID, indicator_type_code: indicator.indicator_type_code as string, unit_name: String(task.unit_name || task.unit_raw || indicator.unit_raw || indicator.unit_code || ''), calc_method: indicator.calc_method as GanttNode['calc_method'], milestones: ((task.milestones || []) as Milestone[]).map(milestonePayload) })),
    })),
  }))
}

export const executionApi = {
  leaderDashboard: (planId: ID) => get<LeaderDashboard>('/dashboard/overview', { plan_id: planId }),
  todos: (params?: PageQuery) => getList<TodoItem>('/todos', params),
  readTodo: (id: ID) => post<TodoItem>(`/todos/${id}/read`, {}, commandConfig()),
  tasks: async (params?: PageQuery) => {
    const [result, organizations, plans] = await Promise.all([getList<CollegeTask>('/tasks', params), getList<{ id: ID; org_name: string }>('/organizations').catch(() => ({ items: [], total: 0 })), getList<{ id: ID; plan_name: string }>('/plans').catch(() => ({ items: [], total: 0 }))])
    result.items = result.items.map((item) => ({ ...item, college_org_name: item.college_org_name || organizations.items.find((org) => org.id === item.college_org_id)?.org_name, plan_name: item.plan_name || plans.items.find((plan) => plan.id === item.plan_id)?.plan_name }))
    return result
  },
  task: readTask,
  previewDecomposition: (indicatorId: ID, data: Record<string, unknown>) => post<Record<string, unknown>>(`/indicators/${indicatorId}/assignment-preview`, data),
  assignTasks: (indicatorId: ID, data: Record<string, unknown>) => post<ListResult<CollegeTask>>(`/indicators/${indicatorId}/assign`, data, commandConfig()),
  revokeTask: (id: ID, reason: string, lock_version?: number) => post<CollegeTask>(`/tasks/${id}/revoke`, withLock({ reason }, lock_version), commandConfig()),
  receiveTask: (id: ID, lock_version?: number) => post<CollegeTask>(`/tasks/${id}/receive`, withLock({}, lock_version), commandConfig()),
  submitObjection: (id: ID, content: string) => post<CollegeTask>(`/tasks/${id}/objections`, { objection_text: content }, commandConfig()),
  replyObjection: (objectionId: ID, reply: string, lock_version?: number) => post<CollegeTask>(`/objections/${objectionId}/reply`, withLock({ reply_text: reply, objection_status: 'RESOLVED' }, lock_version), commandConfig()),
  submission: readSubmission,
  milestoneSubmissions: (milestoneId: ID) => getList<Submission>(`/milestones/${milestoneId}/submissions`, { page_size: 100 }),
  saveDraft: (milestoneId: ID, data: Partial<Submission>, lock_version?: number, submissionId?: ID) => {
    const payload = withLock(pick(data, submissionFields), lock_version)
    return submissionId
      ? patch<Submission>(`/submissions/${submissionId}`, payload, commandConfig()).then((item) => submissionPayload(item))
      : post<Submission>(`/milestones/${milestoneId}/draft`, payload, commandConfig()).then((item) => submissionPayload(item))
  },
  reviseSubmission: async (id: ID) => {
    const old = await readSubmission(id)
    const payload = withLock(pick(old, submissionFields), old.milestone?.lock_version)
    return post<Submission>(`/milestones/${old.milestone_id}/draft`, payload, commandConfig())
  },
  submitSubmission: (id: ID, lock_version?: number) => post<Submission>(`/submissions/${id}/submit`, withLock({ confirm: true }, lock_version), commandConfig()),
  withdrawSubmission: (id: ID, reason: string, lock_version?: number) => post<Submission>(`/submissions/${id}/withdraw`, withLock({ reason }, lock_version), commandConfig()),
  uploadMaterial: (submissionId: ID, file: File, material_desc = '') => {
    const data = new FormData()
    data.append('file', file)
    data.append('material_desc', material_desc)
    return post<EvidenceMaterial>(`/submissions/${submissionId}/materials`, data, { headers: { 'Idempotency-Key': crypto.randomUUID?.() || `${Date.now()}-${file.name}` }, timeout: 120000 })
  },
  deleteMaterial: (id: ID) => del<void>(`/materials/${id}`, commandConfig()),
  retryMaterialPreview: (id: ID) => post<EvidenceMaterial>(`/materials/${id}/preview-retry`, {}, commandConfig()),
  materialPreviewUrl: (material: EvidenceMaterial) => material.preview_url?.startsWith('/api/') ? material.preview_url : `/api/v1/materials/${material.id}/preview`,
  reviewQueue: async (params?: PageQuery): Promise<ListResult<Submission>> => {
    const [result, organizations, plans] = await Promise.all([getList<SubmissionPayload & { submission?: SubmissionPayload }>('/reviews', params), getList<{ id: ID; org_name: string }>('/organizations'), getList<{ id: ID; plan_name: string }>('/plans')])
    const normalized = result.items.map((item) => {
      const merged = item.submission ? { ...item.submission, ...item, milestone: item.milestone || item.submission.milestone, task: item.task || item.submission.task, indicator: item.indicator || item.submission.indicator } : item
      const planName = plans.items.find((plan) => plan.id === merged.indicator?.plan_id)?.plan_name
      const collegeOrgName = organizations.items.find((org) => org.id === merged.task?.college_org_id)?.org_name
      return submissionPayload({ ...merged, plan_name: merged.plan_name || planName, college_org_name: merged.college_org_name || collegeOrgName })
    }).filter((item) => !item.submission_status || item.submission_status === 'UNDER_REVIEW')
    const items = await Promise.all(normalized.map(async (item) => {
      const missingContext = !item.target_year || (!item.task_id && !item.task?.id) || (!item.indicator_name && !item.indicator?.indicator_name)
      return missingContext ? readSubmission(item.id) : item
    }))
    return { items, total: result.total }
  },
  reviews: async (submissionId: ID) => {
    const data = await readSubmission(submissionId)
    const result = normalizeList<ReviewRecord>(data.reviews || [])
    result.items.reverse()
    return result
  },
  review: (submissionId: ID, data: Record<string, unknown>, lock_version?: number) => post<ReviewRecord>(`/submissions/${submissionId}/review`, withLock({ ...pick(data, ['review_result', 'review_comment', 'recognized_value', 'recognized_bool', 'recognized_text', 'recognized_numerator', 'recognized_denominator', 'recognition_basis']), confirm: true }, lock_version), commandConfig()),
  correctReview: (reviewId: ID, data: Record<string, unknown>, lock_version?: number) => post<ReviewRecord>(`/reviews/${reviewId}/correct`, withLock({ ...pick(data, ['correction_reason', 'review_comment', 'recognized_value', 'recognized_bool', 'recognized_text', 'recognized_numerator', 'recognized_denominator', 'recognition_basis']), confirm: true }, lock_version), commandConfig()),
  externalIndicatorRecognition: (indicatorId: ID, data: Record<string, unknown>) => post<Record<string, unknown>>(`/indicators/${indicatorId}/external-recognitions`, pick(data, ['target_year', 'recognized_value', 'recognized_bool', 'recognized_text', 'external_source', 'recognition_note']), commandConfig()),
  gantt: async (params?: PageQuery) => {
    const data = await get<{ items: Array<Record<string, unknown>> }>('/gantt', params)
    return ganttNodes(data.items || [])
  },
  milestone: async (id: ID) => {
    const data = await get<Milestone & { latest_submission?: Submission; materials?: EvidenceMaterial[]; reviews?: ReviewRecord[]; adjustments?: Record<string, Array<Record<string, unknown>>> }>(`/gantt/milestones/${id}`)
    const latest = data.latest_submission ? { ...data.latest_submission, materials: data.materials || [] } : undefined
    const adjustments = Object.entries(data.adjustments || {}).flatMap(([type, items]) => items.map((item) => ({ ...item, adjustment_type: type })))
    return { ...milestonePayload(data), latest_submission: latest, submissions: latest ? [latest] : [], reviews: data.reviews || [], adjustments } as Milestone & { submissions?: Submission[]; reviews?: ReviewRecord[]; adjustments?: Record<string, unknown>[] }
  },
}
