import { http } from '@/utils/request'
import type {
  PendingTrainingReviewQuery,
  PendingTrainingReviewResult,
  ReviewTrainingSheetRequest,
  SaveTrainingProjectsRequest,
  SubmitTrainingSheetRequest,
  TrainingSheetDetail,
  TrainingSheetSummary,
  TrainingStaffUpdateRequest,
  TrainingStaffView,
} from '@/types/training'

export function listTrainingSheets(planId?: string): Promise<TrainingSheetSummary[]> {
  return http.get('/training-sheets', { planId })
}

export function getTrainingSheet(sheetId: string): Promise<TrainingSheetDetail> {
  return http.get(`/training-sheets/${sheetId}`)
}

export function getTrainingStaff(sheetId: string): Promise<TrainingStaffView> {
  return http.get(`/training-sheets/${sheetId}/staff`)
}

export function updateTrainingStaff(
  sheetId: string,
  data: TrainingStaffUpdateRequest,
): Promise<TrainingStaffView> {
  return http.put(`/training-sheets/${sheetId}/staff`, data)
}

export function saveTrainingProjects(
  sheetId: string,
  data: SaveTrainingProjectsRequest,
): Promise<TrainingSheetDetail> {
  return http.put(`/training-sheets/${sheetId}/projects`, data)
}

export function submitTrainingSheet(
  sheetId: string,
  data: SubmitTrainingSheetRequest,
  idempotencyKey?: string,
): Promise<TrainingSheetDetail> {
  return http.post(
    `/training-sheets/${sheetId}/submit`,
    data,
    idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
  )
}

export function listPendingTrainingReviews(
  params: PendingTrainingReviewQuery = {},
): Promise<PendingTrainingReviewResult> {
  return http.get('/training-reviews/pending', { ...params })
}

export function reviewTrainingSheet(
  sheetId: string,
  data: ReviewTrainingSheetRequest,
  idempotencyKey?: string,
): Promise<TrainingSheetDetail> {
  return http.post(
    `/training-sheets/${sheetId}/review`,
    data,
    idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
  )
}

export function exportTrainingSheet(sheetId: string): Promise<Blob> {
  return http.get(`/training-sheets/${sheetId}/export`, undefined, { responseType: 'blob' })
}
