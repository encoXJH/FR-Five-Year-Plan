import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  CreateSubmissionRequest,
  SubmissionDetail,
  SubmissionListItem,
  UpdateSubmissionRequest,
} from '@/types/submission'

/** GET /tasks/{taskId}/submissions（后端为分页响应，此处展开为数组） */
export async function listSubmissions(taskId: string): Promise<SubmissionListItem[]> {
  const result = await http.get<PageResult<SubmissionListItem>>(`/tasks/${taskId}/submissions`)
  return result.items
}

export function createSubmission(
  taskId: string,
  data: CreateSubmissionRequest,
): Promise<SubmissionDetail> {
  return http.post(`/tasks/${taskId}/submissions`, data)
}

export function getSubmission(submissionId: string): Promise<SubmissionDetail> {
  return http.get(`/submissions/${submissionId}`)
}

export function updateSubmission(
  submissionId: string,
  data: UpdateSubmissionRequest,
): Promise<SubmissionDetail> {
  return http.patch(`/submissions/${submissionId}`, data)
}

export function deleteSubmission(submissionId: string): Promise<null> {
  return http.delete(`/submissions/${submissionId}`)
}

/** 提交审核（幂等，API 15.5 ⚡）；代填原因在草稿保存时随 proxyReason 提交 */
export function submitSubmission(
  submissionId: string,
  version: number,
  idempotencyKey: string,
): Promise<SubmissionDetail> {
  return http.post(
    `/submissions/${submissionId}/submit`,
    { version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

/** 撤回（幂等，审核前才可以） */
export function withdrawSubmission(
  submissionId: string,
  version: number,
  idempotencyKey: string,
): Promise<SubmissionDetail> {
  return http.post(
    `/submissions/${submissionId}/withdraw`,
    { version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}
