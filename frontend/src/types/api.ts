export type ID = number | string
export type Numeric = number | string
export type Nullable<T> = T | null | undefined
export type RoleCode = 'ADMIN' | 'SCHOOL_LEADER' | 'SCHOOL_OWNER' | 'COLLEGE_OWNER'
export type CalcMethod = 'CUMULATIVE' | 'ANNUAL_QUOTA' | 'FINAL' | 'RATIO' | 'GROWTH' | 'BOOLEAN' | 'EXTERNAL'
export type ValueKind = 'DECIMAL' | 'BOOLEAN' | 'RATIO' | 'PERCENT' | 'PERCENT_YEAR' | 'COMPOUND_DECIMAL' | 'NONE'

export interface ListResult<T> { items: T[]; total: number }
export interface PageQuery { page?: number; page_size?: number; [key: string]: unknown }
export interface AuditFields {
  id: ID
  created_at?: string
  updated_at?: string
  created_by?: ID
  updated_by?: ID
  lock_version?: number
}

export interface SessionUser {
  id: ID
  account_name: string
  user_name: string
  primary_org_id?: ID
  primary_org_name?: string
  college_org_id?: ID
  roles: RoleCode[]
  role_codes?: RoleCode[]
  permissions?: string[]
  project_ids?: ID[]
}

export interface Organization extends AuditFields {
  org_code: string
  org_name: string
  org_short_name?: string
  org_type: 'COLLEGE' | 'SCHOOL_DEPT' | 'OTHER'
  parent_id?: ID
  sort_no: number
  status: string
}
export interface OrgAlias extends AuditFields {
  org_id?: ID
  org_name?: string
  alias_name: string
  source_name?: string
  confirm_status: 'PENDING' | 'CONFIRMED' | 'REJECTED'
}
export interface UserAccount extends AuditFields {
  account_name: string
  user_name: string
  primary_org_id: ID
  primary_org_name?: string
  status: string
  last_login_at?: string
  roles?: RoleCode[]
}
export interface ProjectMember extends AuditFields {
  plan_id: ID
  plan_name?: string
  user_id: ID
  user_name?: string
  account_name?: string
  org_id?: ID
  org_name?: string
  member_role: 'SCHOOL_OWNER' | 'COLLEGE_OWNER' | 'COLLABORATOR'
  is_primary: boolean | number
  effective_from: string
  effective_to?: string
  handover_from_id?: ID
}
export interface DictionaryItem extends AuditFields {
  id: ID
  code?: string
  name?: string
  type_code?: string
  type_name?: string
  unit_code?: string
  unit_name?: string
  dict_type?: string
  dict_code?: string
  dict_name?: string
  value_kind?: ValueKind
  display_suffix?: string
  is_compound?: boolean | number
  is_builtin?: boolean | number
  sort_no?: number
  status: string
  description?: string
}
export interface PlatformSetting extends AuditFields {
  setting_key: string
  setting_value: string | number | boolean | Record<string, unknown>
  value_type?: string
  description?: string
  is_public?: boolean
}

export interface Directory extends AuditFields {
  directory_code: string
  directory_name: string
  description?: string
  leader_user_id: ID
  leader_user_name?: string
  status: string
  sort_no: number
  plan_count?: number
}
export interface ConstructionPlan extends AuditFields {
  plan_code: string
  directory_id: ID
  directory_name?: string
  plan_name: string
  construction_scope: string
  start_year: number
  end_year: number
  school_owner_id: ID
  school_owner_name?: string
  template_source_plan_id?: ID
  copy_mode?: 'STRUCTURE_ONLY' | 'STRUCTURE_AND_TARGET'
  plan_status: 'DRAFT' | 'PUBLISHED' | 'IN_PROGRESS' | 'CLOSED'
  published_at?: string
  current_version_no: number
  direction_count?: number
  indicator_count?: number
  task_count?: number
  completion_summary?: Record<string, number>
}
export interface Direction extends AuditFields {
  plan_id: ID
  direction_code: string
  direction_name: string
  source_seq?: number
  description?: string
  sort_no: number
  status: string
  indicators?: Indicator[]
  indicator_count?: number
}
export interface AnnualTarget extends Partial<AuditFields> {
  indicator_id?: ID
  task_id?: ID
  target_year: number
  professional_target_value?: Numeric | null
  professional_target_bool?: boolean | number | null
  professional_target_text?: string | null
  professional_target_raw?: string | null
  school_target_value?: Numeric | null
  school_target_bool?: boolean | number | null
  school_target_text?: string | null
  school_target_raw?: string | null
  school_division_raw?: string | null
  original_target_value?: Numeric | null
  original_target_bool?: boolean | number | null
  original_target_text?: string | null
  current_target_value?: Numeric | null
  current_target_bool?: boolean | number | null
  current_target_text?: string | null
  professional_allocation_year_raw?: string | null
  target_version_no?: number
  confirm_status?: string
}
export interface Indicator extends AuditFields {
  plan_id: ID
  direction_id: ID
  direction_name?: string
  indicator_code: string
  indicator_name: string
  indicator_desc: string
  indicator_type_code: string
  indicator_type_name?: string
  unit_code: string
  unit_name?: string
  standard_unit_code?: string
  unit_raw: string
  value_kind?: ValueKind
  target_direction?: 'GE' | 'LE' | 'EQ'
  calc_method?: CalcMethod
  statistical_rule?: string
  professional_five_year_target_value?: Numeric | null
  professional_five_year_target_bool?: boolean | number | null
  professional_five_year_target_text?: string | null
  professional_five_year_target_raw?: string | null
  school_five_year_target_value?: Numeric | null
  school_five_year_target_bool?: boolean | number | null
  school_five_year_target_text?: string | null
  school_five_year_target_raw?: string | null
  baseline_value?: Numeric | null
  baseline_year?: number | null
  numerator_rule?: string
  denominator_rule?: string
  decimal_precision?: number
  external_data_source?: string
  parse_status: string
  indicator_status: string
  target_version_no: number
  annual_targets?: AnnualTarget[]
  departments?: Array<{ org_id: ID; org_name?: string; relation_type: string; responsibility_desc?: string }>
  task_count?: number
}
export interface ImportBatch extends AuditFields {
  plan_id: ID
  plan_name?: string
  file_name: string
  file_hash?: string
  sheet_count: number
  data_row_count: number
  import_status: string
  imported_at: string
  ready_count?: number
  confirm_count?: number
  invalid_count?: number
  duplicate_count?: number
}
export interface ImportRow extends AuditFields {
  import_file_id: ID
  sheet_name: string
  row_number: number
  source_seq_raw?: string
  direction_raw?: string
  indicator_raw?: string
  indicator_type_raw?: string
  indicator_unit_raw?: string
  professional_five_year_raw?: string
  professional_2026_raw?: string
  professional_five_year_allocation_raw?: string
  professional_2026_allocation_raw?: string
  school_five_year_raw?: string
  school_2026_raw?: string
  school_2026_division_raw?: string
  school_2027_division_raw?: string
  school_2028_division_raw?: string
  school_2029_division_raw?: string
  school_2030_division_raw?: string
  parse_status: string
  validation_status?: 'READY' | 'NEED_CONFIRM' | 'INVALID'
  error_message?: string
  is_duplicate?: boolean
  duplicate_action?: 'SKIP' | 'RECREATE'
  indicator_id?: ID
  confirmation?: Record<string, unknown>
}

export interface Milestone extends AuditFields {
  task_id: ID
  target_year: number
  original_target_value?: Numeric | null
  original_target_bool?: boolean | number | null
  original_target_text?: string | null
  current_target_value?: Numeric | null
  current_target_bool?: boolean | number | null
  current_target_text?: string | null
  professional_allocation_year_raw?: string
  recognized_value?: Numeric | null
  recognized_bool?: boolean | number | null
  recognized_text?: string | null
  reported_value?: Numeric | null
  shortfall_value?: Numeric | null
  covered_value?: Numeric | null
  milestone_status: string
  due_date?: string
  current_submission_id?: ID
  last_review_id?: ID
  target_version_no: number
  recognized_at?: string
  has_adjustment: boolean | number
  completion_rate?: Numeric | null
  latest_submission?: Submission
  adjustment_label?: string
}
export interface CollegeTask extends AuditFields {
  task_code: string
  indicator_id: ID
  indicator_name?: string
  indicator_code?: string
  plan_id?: ID
  plan_name?: string
  direction_id?: ID
  direction_name?: string
  college_org_id: ID
  college_org_name?: string
  owner_user_id: ID
  owner_user_name?: string
  execution_scope?: string
  five_year_target_value?: Numeric | null
  five_year_target_bool?: boolean | number | null
  five_year_target_text?: string | null
  unit_code?: string
  unit_name?: string
  unit_raw?: string
  value_kind?: ValueKind
  calc_method?: CalcMethod
  statistical_rule?: string
  indicator_type_code?: string
  task_status: string
  progress_percent?: Numeric | null
  milestone_count?: number
  completed_milestone_count?: number
  assigned_at?: string
  received_at?: string
  current_target_version_no: number
  milestones?: Milestone[]
  objection_id?: ID
  objection_lock_version?: number
  objection_status?: string
  objection_content?: string
  indicator?: Indicator
  objection_reply?: string
}
export interface Submission extends AuditFields {
  milestone_id: ID
  task_id?: ID
  target_year?: number
  task_code?: string
  indicator_name?: string
  plan_name?: string
  college_org_name?: string
  version_no: number
  is_current: boolean | number
  reported_value?: Numeric | null
  reported_bool?: boolean | number | null
  reported_text?: string | null
  reported_numerator?: Numeric | null
  reported_denominator?: Numeric | null
  completion_desc: string
  achievement_name?: string
  completion_date: string
  submission_status: string
  submitted_at?: string
  withdraw_reason?: string
  materials?: EvidenceMaterial[]
  reviews?: ReviewRecord[]
  task?: CollegeTask
  milestone?: Milestone
  indicator?: Indicator
}
export interface EvidenceMaterial extends AuditFields {
  submission_id: ID
  material_name: string
  original_file_name: string
  file_extension: string
  mime_type: string
  file_size_bytes: number
  material_desc?: string
  preview_status: 'PROCESSING' | 'READY' | 'FAILED'
  preview_error?: string
  uploaded_at: string
  preview_url?: string
}
export interface ReviewRecord extends AuditFields {
  submission_id: ID
  review_result: 'APPROVED' | 'RETURNED'
  review_comment: string
  recognized_value?: Numeric | null
  recognized_bool?: boolean | number | null
  recognized_text?: string | null
  recognition_basis?: string
  milestone_result?: string
  reviewer_id: ID
  reviewer_name?: string
  reviewed_at: string
  correction_reason?: string
}
export interface TodoItem extends AuditFields {
  plan_id: ID
  plan_name?: string
  biz_type: string
  biz_id: ID
  title: string
  content_summary?: string
  todo_status: string
  action_url?: string
  target_year?: number
  created_at: string
}
export interface GanttNode {
  id: ID
  node_key?: string
  node_type: 'DIRECTION' | 'INDICATOR' | 'TASK'
  name: string
  code?: string
  plan_id?: ID
  direction_id?: ID
  indicator_id?: ID
  task_id?: ID
  college_org_id?: ID
  owner_user_id?: ID
  indicator_type_code?: string
  unit_name?: string
  calc_method?: CalcMethod
  status_counts?: Record<string, number>
  milestones?: Milestone[]
  children?: GanttNode[]
}
export interface DashboardMilestoneStats {
  milestone_count: number
  completed: number
  under_review: number
  returned: number
  unmet: number
  pending_report: number
  not_started: number
  draft: number
}
export interface LeaderDashboardSummary extends DashboardMilestoneStats {
  direction_count: number
  indicator_count: number
  task_count: number
  completion_percent: number
  reviewed_percent: number
}
export interface LeaderDashboardDirection extends DashboardMilestoneStats {
  id: ID
  direction_code: string
  direction_name: string
  indicator_count: number
  task_count: number
  progress_percent: number
}
export interface LeaderDashboardIndicator extends DashboardMilestoneStats {
  id: ID
  indicator_code: string
  indicator_name: string
  direction_id: ID
  direction_name?: string
  indicator_type_code: string
  calc_method?: CalcMethod
  unit_code?: string
  unit_name?: string
  value_kind?: ValueKind
  task_count: number
  task_status_counts: Record<string, number>
  progress_percent: number
  progress_basis: 'VALUE' | 'COUNT'
  target_value?: Numeric | null
  recognized_value?: Numeric | null
}
export interface LeaderDashboardCollege extends DashboardMilestoneStats {
  college_org_id: ID
  college_org_name: string
  task_count: number
  progress_percent: number
}
export interface LeaderDashboardYear extends DashboardMilestoneStats {
  target_year: number
  completion_percent: number
}
export interface LeaderDashboard {
  plan: ConstructionPlan
  summary: LeaderDashboardSummary
  directions: LeaderDashboardDirection[]
  indicators: LeaderDashboardIndicator[]
  colleges: LeaderDashboardCollege[]
  years: LeaderDashboardYear[]
}
export interface Carryover extends AuditFields {
  carryover_no: string
  task_id: ID
  task_code?: string
  indicator_name?: string
  source_milestone_id: ID
  source_year?: number
  target_milestone_id: ID
  target_year?: number
  carryover_amount: Numeric
  reason: string
  carryover_status: string
  initiated_by: ID
  initiator_name?: string
  initiated_at: string
  before_snapshot_json?: Record<string, unknown>
  after_snapshot_json?: Record<string, unknown>
}
export interface Compensation extends AuditFields {
  task_id: ID
  task_code?: string
  indicator_name?: string
  deficit_milestone_id: ID
  deficit_year?: number
  surplus_milestone_id: ID
  surplus_year?: number
  compensation_amount: Numeric
  compensation_status: string
  reversed_reason?: string
}
export interface TransferApproval extends AuditFields {
  transfer_id: ID
  step_code: 'OUT_COLLEGE' | 'IN_COLLEGE' | 'SCHOOL_LEADER'
  step_status: string
  approver_org_id?: ID
  approver_org_name?: string
  approver_user_id?: ID
  approver_user_name?: string
  action_comment?: string
  acted_at?: string
}
export interface TransferAttachment {
  id?: ID
  name?: string
  object_key: string
  original_file_name?: string
  file_name?: string
  mime_type?: string
  preview_url?: string
  url?: string
  attachment_url?: string
  download_url?: string
}
export interface Transfer extends AuditFields {
  transfer_no: string
  indicator_id: ID
  indicator_name?: string
  plan_id?: ID
  plan_name?: string
  source_task_id: ID
  source_task_code?: string
  source_college_org_id?: ID
  source_college_org_name?: string
  target_college_org_id: ID
  target_college_org_name?: string
  target_task_id?: ID
  transfer_year?: number
  transfer_amount: Numeric
  reason: string
  attachment_json?: TransferAttachment[]
  before_snapshot_json?: Record<string, unknown>
  after_snapshot_json?: Record<string, unknown>
  transfer_status: string
  initiated_by: ID
  initiated_at: string
  terminated_reason?: string
  approvals?: TransferApproval[]
}
export interface OperationLog extends AuditFields {
  log_no: string
  operator_user_id: ID
  operator_user_name?: string
  operator_role_code: RoleCode
  plan_id?: ID
  plan_name?: string
  college_org_name?: string
  object_type: string
  object_id: ID
  object_name?: string
  action_code: string
  operation_reason?: string
  before_snapshot_json?: Record<string, unknown>
  after_snapshot_json?: Record<string, unknown>
  related_order_type?: string
  related_order_id?: ID
  operated_at: string
}
