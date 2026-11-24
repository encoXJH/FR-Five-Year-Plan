import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listReviewHistory } from '../review'
import { http } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn() },
}))

const getMock = http.get as unknown as ReturnType<typeof vi.fn>

describe('历史审核 API 契约', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  it('按审核结果、计划、学院和关键字查询历史记录', async () => {
    const result = { items: [], total: 0, page: 1, pageSize: 20 }
    getMock.mockResolvedValue(result)

    await expect(listReviewHistory({
      status: 'APPROVED',
      planId: 'plan-1',
      unitId: 'unit-1',
      keyword: 'A01',
      page: 1,
      pageSize: 20,
    })).resolves.toBe(result)

    expect(getMock).toHaveBeenCalledWith('/reviews/history', {
      status: 'APPROVED',
      planId: 'plan-1',
      unitId: 'unit-1',
      keyword: 'A01',
      page: 1,
      pageSize: 20,
    })
  })
})
