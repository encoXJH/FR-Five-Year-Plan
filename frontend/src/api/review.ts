import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  BatchReviewItemResult,
  BatchReviewRequest,
  PendingReviewItem,
  PendingReviewQuery,
  ReviewRequest,
  SubmissionDetail,
} from '@/types/submission'

export function listPendingReviews(
  query: PendingReviewQuery = {},
): Promise<PageResult<PendingReviewItem>> {
  return http.get('/reviews/pending', { ...query })
}

/** 审核（幂等，API 15.5 ⚡）：通过 / 驳回 / 核减 */
export function reviewSubmission(
  submissionId: string,
  data: ReviewRequest,
  idempotencyKey: string,
): Promise<SubmissionDetail> {
  return http.post(`/submissions/${submissionId}/review`, data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

type BatchReviewResponse =
  | BatchReviewItemResult[]
  | { results?: BatchReviewItemResult[]; items?: BatchReviewItemResult[] }
  | null

/** 批量审核通过（幂等，逐条独立事务，单条失败不影响其他条）；归一化为逐条结果数组 */
export async function batchReview(
  data: BatchReviewRequest,
  idempotencyKey: string,
): Promise<BatchReviewItemResult[]> {
  const response = await http.post<BatchReviewResponse>('/submissions/batch-review', data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  if (Array.isArray(response)) return response
  return response?.results ?? response?.items ?? []
}
