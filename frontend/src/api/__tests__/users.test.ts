import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listStaffUsers, resetStaffUserPassword, updateStaffUser } from '../system'
import { http } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn(), patch: vi.fn(), post: vi.fn() },
}))

const getMock = http.get as unknown as ReturnType<typeof vi.fn>
const patchMock = http.patch as unknown as ReturnType<typeof vi.fn>
const postMock = http.post as unknown as ReturnType<typeof vi.fn>

describe('本学院干事用户管理 API', () => {
  beforeEach(() => {
    getMock.mockReset()
    patchMock.mockReset()
    postMock.mockReset()
  })

  it('按学院、状态和分页查询纯干事', async () => {
    getMock.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
    await listStaffUsers({ unitId: '101', keyword: '张', isEnabled: true, page: 1, pageSize: 20 })
    expect(getMock).toHaveBeenCalledWith('/users/staff', {
      unitId: '101', keyword: '张', isEnabled: true, page: 1, pageSize: 20,
    })
  })

  it('编辑和重置密码均携带版本号', async () => {
    patchMock.mockResolvedValue({ id: '1' })
    postMock.mockResolvedValue(undefined)
    await updateStaffUser('1', { name: '干事', unitId: '101', version: 2 })
    await resetStaffUserPassword('1', { password: 'NewPassword@2026', version: 3 })
    expect(patchMock).toHaveBeenCalledWith('/users/staff/1', { name: '干事', unitId: '101', version: 2 })
    expect(postMock).toHaveBeenCalledWith('/users/staff/1/password-reset', {
      password: 'NewPassword@2026', version: 3,
    })
  })
})
