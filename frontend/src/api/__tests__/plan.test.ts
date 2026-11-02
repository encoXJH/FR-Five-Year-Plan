import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listPlans } from '../plan'
import { http } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn() },
}))

const getMock = http.get as unknown as ReturnType<typeof vi.fn>

const responseItem = {
  id: '1',
  name: '学院关联计划',
  startYear: 2026,
  endYear: 2030,
  baselineYear: 2025,
  status: 'ACTIVE',
  completionRate: 0.5,
  createdAt: '2026-01-01T08:00:00+08:00',
  updatedAt: null,
  indicatorCountA: 1,
  indicatorCountAPlus: 2,
  taskCountB: 3,
}

describe('计划列表卡片映射', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  it('映射完成率和数量，并在更新时间为空时回退创建时间', async () => {
    getMock.mockResolvedValue([responseItem])

    const [plan] = await listPlans()

    expect(plan).toMatchObject({
      overallCompletionRate: 0.5,
      yearCompletionRate: 0.5,
      indicatorCountA: 1,
      indicatorCountAPlus: 2,
      taskCountB: 3,
      updatedAt: responseItem.createdAt,
    })
  })

  it('保留分页返回结构中的计划项', async () => {
    getMock.mockResolvedValue({ items: [responseItem], total: 1, page: 1, pageSize: 20 })

    await expect(listPlans()).resolves.toHaveLength(1)
  })

  it('有学院任务但完成率为空时按 0% 展示', async () => {
    getMock.mockResolvedValue([{ ...responseItem, completionRate: null, taskCountB: 2 }])

    const [plan] = await listPlans()

    expect(plan.overallCompletionRate).toBe(0)
    expect(plan.yearCompletionRate).toBe(0)
  })

  it('无学院任务且完成率为空时保持空值', async () => {
    getMock.mockResolvedValue([{ ...responseItem, completionRate: null, taskCountB: 0 }])

    const [plan] = await listPlans()

    expect(plan.overallCompletionRate).toBeNull()
  })
})
