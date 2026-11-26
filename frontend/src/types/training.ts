import type { PageResult, UserRef } from './api'
import type { ReviewResult, TrainingSheetStatus } from './enums'
import type { FileInfo } from './file'
import type { ReviewNode, SubmitterRole } from './submission'

export type { TrainingSheetStatus } from './enums'

export type TrainingFieldType = 'TEXT' | 'SELECT' | 'DATE' | 'DECIMAL' | 'INTEGER'

export type TrainingCellValue = string | number | null

export type TrainingProjectData = Record<string, TrainingCellValue>

export interface TrainingSheetSummary {
  id: string
  planId: string
  planName: string
  startYear: number
  endYear: number
  baselineYear: number
  unitId: string
  ownerId: string
  status: TrainingSheetStatus
  projectCount: number
  version: number
  updatedAt: string
  unitName: string | null
  ownerName: string | null
  submitterRole: SubmitterRole | null
  currentReviewNode: ReviewNode | null
  revisionNo: number
  submittedBy: string | null
  submittedByName: string | null
  submittedAt: string | null
  canEdit: boolean
  canReview: boolean
  canAssignStaff: boolean
}

export interface TrainingStaffView {
  canAssignStaff: boolean
  version: number
  staff: UserRef[]
  assignableStaff: UserRef[]
}

export interface TrainingStaffUpdateRequest {
  version: number
  staffIds: string[]
}

export interface TrainingField {
  key: string
  column: number
  label: string
  group: string
  groupLabel: string
  type: TrainingFieldType
  required: boolean
  options: string[]
}

export interface TrainingProject {
  id: string
  rowNo: number
  data: TrainingProjectData
  files: FileInfo[]
  status: TrainingSheetStatus
  submitterRole: SubmitterRole | null
  currentReviewNode: ReviewNode | null
  revisionNo: number
  submittedBy: string | null
  submittedAt: string | null
  canEdit: boolean
  canReview: boolean
  reviewComment: string | null
}

export interface TrainingProjectInput {
  id?: string | null
  rowNo: number
  data: TrainingProjectData
  fileIds?: string[]
  files?: FileInfo[]
  status?: TrainingSheetStatus
  submitterRole?: SubmitterRole | null
  currentReviewNode?: ReviewNode | null
  revisionNo?: number
  submittedBy?: string | null
  submittedAt?: string | null
  canEdit?: boolean
  canReview?: boolean
  reviewComment?: string | null
}

export interface TrainingReviewItem {
  id: string
  projectId: string
  rowNo: number
  revisionNo: number
  reviewNode: ReviewNode
  result: ReviewResult
  comment: string | null
  reviewerId: string
  reviewerName: string | null
  isProxy: boolean
  proxyReason: string | null
  createdAt: string
}

export interface TrainingSheetDetail {
  sheet: TrainingSheetSummary
  fields: TrainingField[]
  projects: TrainingProject[]
  reviews: TrainingReviewItem[]
}

export interface SaveTrainingProjectsRequest {
  version: number
  projects: TrainingProjectInput[]
}

export interface SubmitTrainingSheetRequest {
  version: number
  projectIds: string[]
}

export interface ReviewTrainingSheetRequest {
  projectId: string
  version: number
  result: ReviewResult
  comment?: string
  reason?: string
}

export interface PendingTrainingReviewQuery {
  planId?: string
  unitId?: string
  reviewNode?: ReviewNode
  page?: number
  pageSize?: number
}

export type PendingTrainingReviewResult = PageResult<TrainingSheetSummary>
