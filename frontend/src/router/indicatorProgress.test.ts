import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '@/types/enums'

const userStore = vi.hoisted(() => ({
  isLoggedIn: true,
  userInfo: { id: 'u1' } as { id: string } | null,
  roles: ['UNIT_OWNER'] as Role[],
  permissions: [] as string[],
  homePath: '/workbench',
  can: vi.fn(() => false),
  fetchMe: vi.fn(),
}))

const statsApi = vi.hoisted(() => ({
  getStatsTree: vi.fn(),
  getStatsOverview: vi.fn(),
  getIndicatorTrend: vi.fn(),
}))

vi.mock('@/stores/user', () => ({ useUserStore: () => userStore }))
vi.mock('@/api/stats', () => statsApi)
vi.mock('@/views/error/Forbidden.vue', () => ({ default: {} }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    createWebHistory: () => actual.createMemoryHistory(),
  }
})

import router from './index'

vi.stubGlobal('document', { title: '' })

describe('指标进度可视化路由权限', () => {
  beforeEach(async () => {
    userStore.isLoggedIn = true
    userStore.userInfo = { id: 'u1' }
    userStore.roles = ['ADMIN']
    userStore.homePath = '/plans'
    await router.replace('/403')
    userStore.roles = ['UNIT_OWNER']
    userStore.homePath = '/workbench'
    vi.clearAllMocks()
  })

  it('纯 UNIT_OWNER 无法进入指标总表中的趋势与进度入口', async () => {
    await router.push('/plans/123/indicators')

    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('纯 UNIT_OWNER 直访进入 403，且不发起统计请求', async () => {
    await router.push('/plans/123/indicator-progress')

    expect(router.currentRoute.value.path).toBe('/403')
    expect(statsApi.getStatsTree).not.toHaveBeenCalled()
    expect(statsApi.getStatsOverview).not.toHaveBeenCalled()
    expect(statsApi.getIndicatorTrend).not.toHaveBeenCalled()
  })

  it('UNIT_OWNER 兼任 SCHOOL_OWNER 时保留访问能力', async () => {
    userStore.roles = ['UNIT_OWNER', 'SCHOOL_OWNER']
    userStore.homePath = '/plans'

    await router.push('/plans/123/indicator-progress')

    expect(router.currentRoute.value.path).toBe('/plans/123/indicator-progress')
  })
})
