import { beforeEach, describe, expect, it, vi } from 'vitest'

const userStore = vi.hoisted(() => ({
  isLoggedIn: true,
  userInfo: null as { id: string } | null,
  homePath: '/workbench',
  isStaffOnly: true,
  roles: ['STAFF'],
  can: vi.fn(() => false),
  fetchMe: vi.fn(),
  fetchEnums: vi.fn(),
}))

vi.mock('@/stores/user', () => ({ useUserStore: () => userStore }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, createWebHistory: () => actual.createMemoryHistory() }
})

import router from './index'

vi.stubGlobal('document', { title: '' })

describe('H5 离线登录路由', () => {
  beforeEach(async () => {
    userStore.isLoggedIn = true
    userStore.userInfo = null
    userStore.fetchMe.mockReset()
    vi.stubGlobal('document', { title: '' })
    if (router.currentRoute.value.path !== '/login') await router.replace('/login')
  })

  it('auth/me 网络失败时停留登录页且只请求一次', async () => {
    userStore.fetchMe.mockRejectedValueOnce(new Error('ECONNREFUSED'))

    await router.push('/workbench')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(userStore.fetchMe).toHaveBeenCalledTimes(1)
  })
})
