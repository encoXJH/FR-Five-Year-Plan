import { beforeEach, describe, expect, it, vi } from 'vitest'
import { updateTaskStaff } from '../task'
import { http } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}))

const putMock = http.put as unknown as ReturnType<typeof vi.fn>

describe('任务干事分配 API', () => {
  beforeEach(() => {
    putMock.mockReset()
  })

  it('一次提交完整 staffIds 和任务 version', async () => {
    const detail = { id: '9001', version: 8 }
    const request = { staffIds: ['1001', '1002'], version: 7 }
    putMock.mockResolvedValue(detail)

    await expect(updateTaskStaff('9001', request)).resolves.toBe(detail)
    expect(putMock).toHaveBeenCalledWith('/tasks/9001/staff', request)
  })

  it('空数组表示移除全部干事', async () => {
    putMock.mockResolvedValue({ id: '9001' })
    const request = { staffIds: [], version: 8 }

    await updateTaskStaff('9001', request)

    expect(putMock).toHaveBeenCalledWith('/tasks/9001/staff', request)
  })
})
