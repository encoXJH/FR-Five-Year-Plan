import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listPendingReviews, reviewSubmission } from '../review'
import { http } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn(), post: vi.fn() },
}))

const getMock = http.get as unknown as ReturnType<typeof vi.fn>
const postMock = http.post as unknown as ReturnType<typeof vi.fn>

describe('两级审核 API', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
  })

  it('待审查询透传 reviewNode 及既有筛选条件', async () => {
    getMock.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })

    await listPendingReviews({ planId: '11', reviewNode: 'UNIT_REVIEW', keyword: 'A01' })

    expect(getMock).toHaveBeenCalledWith('/reviews/pending', {
      planId: '11',
      reviewNode: 'UNIT_REVIEW',
      keyword: 'A01',
    })
  })

  it('审核请求只传结果、认定值和版本，不信任客户端传节点', async () => {
    postMock.mockResolvedValue({ id: '7' })
    const body = {
      result: 'APPROVED' as const,
      approvedValue: { amount: 2 },
      comment: '院审核减',
      version: 3,
    }

    await reviewSubmission('7', body, 'idem-1')

    expect(postMock).toHaveBeenCalledWith('/submissions/7/review', body, {
      headers: { 'Idempotency-Key': 'idem-1' },
    })
    expect(body).not.toHaveProperty('currentReviewNode')
    expect(body).not.toHaveProperty('reviewNode')
  })
})
