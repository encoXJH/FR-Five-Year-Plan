/** 枚举类型（API 约定第 9 章），中文名以 GET /dicts/enums 返回为准 */

export type PlanStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'

export type PlanSource = 'BLANK' | 'TEMPLATE' | 'COPY'

export type SubjectType = 'SCHOOL' | 'MAJOR_GROUP'

export type IndicatorLevel = 'A' | 'A_PLUS' | 'B'

export type IndicatorNature = 'CONSTRAINT' | 'DEVELOPMENT'

export type MeasureType = 'NUMBER' | 'BOOLEAN' | 'PERCENT_QUANTIFIABLE' | 'PERCENT_DIRECT'

export type StatCaliber = 'INCREMENT' | 'STOCK' | 'GROWTH' | 'RATIO' | 'DIRECT_PERCENT' | 'BOOLEAN'

export type TargetDirection = 'HIGHER_BETTER' | 'LOWER_BETTER'

export type RollupMode = 'AUTO_SUM' | 'MANUAL'

export type OrgUnitType = 'COLLEGE' | 'INTERNATIONAL_COLLEGE' | 'MARXISM_COLLEGE' | 'SCHOOL_OFFICE'

export type Role = 'ADMIN' | 'LEADER' | 'SCHOOL_OWNER' | 'UNIT_OWNER'

export type TaskStatus =
  | 'PENDING_DISTRIBUTION'
  | 'DISTRIBUTED'
  | 'IN_PROGRESS'
  | 'TRANSFERRED_OUT'
  | 'CLOSED'

export type TaskYearStatus =
  | 'NOT_ACTIVATED'
  | 'IN_PROGRESS'
  | 'PENDING_REVIEW'
  | 'ADJUSTING'
  | 'ACHIEVED'
  | 'NOT_ACHIEVED'
  | 'MAKEUP_PENDING'
  | 'MADE_UP'

export type SubmissionStatus = 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'

export type ReviewResult = 'APPROVED' | 'REJECTED'

export type AdjustmentType = 'POSTPONE' | 'TRANSFER' | 'POSTPONE_TRANSFER' | 'TARGET_CHANGE'

export type AdjustmentStatus =
  | 'PENDING_OWNER_REVIEW'
  | 'PENDING_RECEIVER_CONFIRM'
  | 'PENDING_LEADER_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'WITHDRAWN'

export type ApprovalNode = 'OWNER_REVIEW' | 'RECEIVER_CONFIRM' | 'LEADER_APPROVAL'

export type Decision = 'APPROVE' | 'REJECT'

export type MilestoneStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'OVERDUE' | 'POSTPONED'

export type JobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type NotificationType =
  | 'TASK_DISTRIBUTED'
  | 'DEADLINE_REMINDER'
  | 'REVIEW_RESULT'
  | 'ADJUSTMENT_TODO'
  | 'ADJUSTMENT_RESULT'
  | 'WARNING'

export type ClientType = 'WEB' | 'FEISHU_PC' | 'FEISHU_MOBILE' | 'SCREEN'

export type PlanYearStatus = 'NOT_ACTIVATED' | 'ACTIVE' | 'LOCKED'

export type IndicatorStatus = 'DRAFT' | 'FINALIZED' | 'DISTRIBUTED'

export type AchieveStatus =
  | 'NOT_STARTED'
  | 'IN_PROGRESS'
  | 'ACHIEVED'
  | 'EXCEEDED'
  | 'NOT_ACHIEVED'
  | 'LAGGING'

export type FileBizType =
  | 'SUBMISSION_ATTACHMENT'
  | 'ADJUSTMENT_ATTACHMENT'
  | 'MILESTONE_ATTACHMENT'
  | 'IMPORT_FILE'

export type FileCategory = 'DOCUMENT' | 'PDF' | 'IMAGE' | 'VIDEO' | 'ARCHIVE'

export type PreviewType = 'IMAGE' | 'PDF' | 'OFFICE' | 'VIDEO' | 'UNSUPPORTED'

export type TranscodeStatus = 'PROCESSING' | 'SUCCEEDED' | 'FAILED'

/** GET /dicts/enums 返回：枚举名 → (枚举值 → 中文名) */
export type EnumDict = Record<string, Record<string, string>>
