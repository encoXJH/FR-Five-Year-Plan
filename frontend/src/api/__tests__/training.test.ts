import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  exportTrainingSheet,
  getTrainingSheet,
  listPendingTrainingReviews,
  listTrainingSheets,
  reviewTrainingSheet,
  saveTrainingProjects,
  submitTrainingSheet,
} from '../training'
import { http } from '@/utils/request'
import type { SaveTrainingProjectsRequest } from '@/types/training'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}))

const getMock = http.get as unknown as ReturnType<typeof vi.fn>
const postMock = http.post as unknown as ReturnType<typeof vi.fn>
const putMock = http.put as unknown as ReturnType<typeof vi.fn>

describe('职业培训采集 API 契约', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    putMock.mockReset()
  })

  it('按可选 planId 查询采集表并读取详情', async () => {
    getMock.mockResolvedValue([])
    await listTrainingSheets()
    await listTrainingSheets('plan-1')
    expect(getMock).toHaveBeenNthCalledWith(1, '/training-sheets', { planId: undefined })
    expect(getMock).toHaveBeenNthCalledWith(2, '/training-sheets', { planId: 'plan-1' })

    const detail = { sheet: { id: 'sheet-1' }, fields: [], projects: [] }
    getMock.mockResolvedValue(detail)
    await expect(getTrainingSheet('sheet-1')).resolves.toBe(detail)
    expect(getMock).toHaveBeenCalledWith('/training-sheets/sheet-1')
  })

  it('整体保存 projects 并携带 sheet version', async () => {
    const request: SaveTrainingProjectsRequest = {
      version: 3,
      projects: [{ id: 'project-1', rowNo: 1, data: { c01: '培训项目' } }, { rowNo: 2, data: {} }],
    }
    putMock.mockResolvedValue({ sheet: { version: 4 } })

    await saveTrainingProjects('sheet-1', request)
    expect(putMock).toHaveBeenCalledWith('/training-sheets/sheet-1/projects', request)
  })

  it('提交所选 projectIds 和 version，并在提供时携带幂等键', async () => {
    postMock.mockResolvedValue({ sheet: { status: 'PENDING_REVIEW' } })

    await submitTrainingSheet('sheet-1', { version: 4, projectIds: ['project-1', 'project-2'] }, 'idem-1')
    expect(postMock).toHaveBeenCalledWith(
      '/training-sheets/sheet-1/submit',
      { version: 4, projectIds: ['project-1', 'project-2'] },
      { headers: { 'Idempotency-Key': 'idem-1' } },
    )
  })

  it('查询培训待审并携带 version 和幂等键提交审核', async () => {
    getMock.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 50 })
    await listPendingTrainingReviews({ reviewNode: 'LEADER_REVIEW', page: 1, pageSize: 50 })
    expect(getMock).toHaveBeenCalledWith('/training-reviews/pending', {
      reviewNode: 'LEADER_REVIEW', page: 1, pageSize: 50,
    })

    postMock.mockResolvedValue({ sheet: { status: 'APPROVED' } })
    await reviewTrainingSheet('sheet-1', { projectId: 'project-1', version: 5, result: 'APPROVED' }, 'review-1')
    expect(postMock).toHaveBeenCalledWith(
      '/training-sheets/sheet-1/review',
      { projectId: 'project-1', version: 5, result: 'APPROVED' },
      { headers: { 'Idempotency-Key': 'review-1' } },
    )
  })

  it('导出接口按 Blob 读取', async () => {
    const blob = new Blob(['xlsx'])
    getMock.mockResolvedValue(blob)

    await expect(exportTrainingSheet('sheet-1')).resolves.toBe(blob)
    expect(getMock).toHaveBeenCalledWith(
      '/training-sheets/sheet-1/export',
      undefined,
      { responseType: 'blob' },
    )
  })
})
