import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const mocks = vi.hoisted(() => ({
  feishuLogin: vi.fn(),
  feishuBind: vi.fn(),
  fetchMe: vi.fn(),
  fetchEnums: vi.fn(),
  saveTokens: vi.fn(),
}))

vi.mock('@/api/auth', () => ({
  feishuLogin: mocks.feishuLogin,
  feishuBind: mocks.feishuBind,
  fetchMe: mocks.fetchMe,
}))

vi.mock('@/api/dict', () => ({
  fetchEnums: mocks.fetchEnums,
}))

vi.mock('@/utils/request', () => ({
  getAccessToken: () => null,
  getRefreshToken: () => null,
  saveTokens: mocks.saveTokens,
  clearTokens: vi.fn(),
}))

vi.mock('@/utils/currentPlan', () => ({
  clearCurrentPlan: vi.fn(),
}))

import { useUserStore } from '../user'

const user = { id: '1', name: '张三', unit: null, roles: ['ADMIN'] }
const tokenResponse = {
  accessToken: 'access',
  accessTokenExpiresAt: '2026-11-05T22:00:00+08:00',
  refreshToken: 'refresh',
  refreshTokenExpiresAt: '2026-11-12T22:00:00+08:00',
  user,
}

describe('飞书首次绑定登录状态', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mocks.fetchMe.mockResolvedValue({
      user,
      roles: ['ADMIN'],
      permissions: [],
      dataScope: { type: 'ALL', unitId: null, indicatorIds: [] },
    })
    mocks.fetchEnums.mockResolvedValue({})
  })

  it('待绑定分支不写 Token，也不加载用户信息', async () => {
    const challenge = {
      accessToken: null,
      accessTokenExpiresAt: null,
      refreshToken: null,
      refreshTokenExpiresAt: null,
      user: null,
      bindRequired: true as const,
      bindingTicket: 'ticket',
      bindingTicketExpiresAt: '2026-11-05T22:00:00+08:00',
    }
    mocks.feishuLogin.mockResolvedValue(challenge)
    const store = useUserStore()

    await expect(store.loginFeishu('code', 'WEB')).resolves.toEqual(challenge)
    expect(mocks.saveTokens).not.toHaveBeenCalled()
    expect(mocks.fetchMe).not.toHaveBeenCalled()
    expect(store.accessToken).toBeNull()
  })

  it('已绑定分支保存 Token 并加载权限', async () => {
    mocks.feishuLogin.mockResolvedValue({
      ...tokenResponse,
      bindRequired: false,
      bindingTicket: null,
      bindingTicketExpiresAt: null,
    })
    const store = useUserStore()

    await expect(store.loginFeishu('code', 'WEB')).resolves.toBeNull()
    expect(mocks.saveTokens).toHaveBeenCalledWith('access', 'refresh')
    expect(mocks.fetchMe).toHaveBeenCalledTimes(1)
    expect(store.roles).toEqual(['ADMIN'])
  })

  it('校内核验绑定成功后才保存 Token', async () => {
    mocks.feishuBind.mockResolvedValue(tokenResponse)
    const store = useUserStore()
    const request = { bindingTicket: 'ticket', name: '张三', employeeId: '10001' }

    await store.bindFeishu(request, 'WEB')
    expect(mocks.feishuBind).toHaveBeenCalledWith(request, 'WEB')
    expect(mocks.saveTokens).toHaveBeenCalledWith('access', 'refresh')
    expect(mocks.fetchMe).toHaveBeenCalledTimes(1)
  })
})
