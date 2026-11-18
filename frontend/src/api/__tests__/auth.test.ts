import { describe, expect, it, vi } from 'vitest'
import { changePassword } from '../auth'
import { http } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  http: { post: vi.fn() },
}))

const postMock = http.post as unknown as ReturnType<typeof vi.fn>

describe('认证 API', () => {
  it('提交新密码和确认密码到自助修改密码接口', async () => {
    postMock.mockResolvedValue(null)
    const request = { password: 'NewPass@123', confirmPassword: 'NewPass@123' }

    await expect(changePassword(request)).resolves.toBeNull()
    expect(postMock).toHaveBeenCalledWith('/auth/change-password', request)
  })
})
