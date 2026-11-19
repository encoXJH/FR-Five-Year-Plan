import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '@/types/enums'

const userStore = vi.hoisted(() => ({
  isLoggedIn: true,
  userInfo: { id: 'u1' } as { id: string } | null,
  roles: ['ADMIN'] as Role[],
  permissions: [] as string[],
  homePath: '/plans',
  can: vi.fn(),
  fetchMe: vi.fn(),
}))

vi.mock('@/stores/user', () => ({ useUserStore: () => userStore }))
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

describe('干事与用户添加路由权限', () => {
  beforeEach(async () => {
    userStore.isLoggedIn = true
    userStore.userInfo = { id: 'u1' }
    userStore.roles = ['ADMIN']
    userStore.permissions = []
    userStore.homePath = '/plans'
    userStore.can.mockImplementation((...codes: string[]) =>
      codes.some((code) => userStore.permissions.includes(code)))
    await router.replace('/403')
    vi.clearAllMocks()
  })

  it('只有 user:create 即可进入用户添加，不要求 system:manage', async () => {
    userStore.roles = ['UNIT_OWNER']
    userStore.permissions = ['user:create']
    userStore.homePath = '/workbench'

    await router.push('/users/create')

    expect(router.currentRoute.value.path).toBe('/users/create')
  })

  it('只有 system:manage 而没有 user:create 时不能进入用户添加', async () => {
    userStore.permissions = ['system:manage']

    await router.push('/users/create')

    expect(router.currentRoute.value.path).toBe('/403')
  })

  it.each([
    { roles: ['STAFF'] as Role[] },
    { roles: ['UNIT_OWNER', 'STAFF'] as Role[] },
  ])('纯执行角色 $roles 隐藏计划首页', async ({ roles }) => {
    userStore.roles = roles
    userStore.homePath = '/workbench'

    await router.push('/plans')

    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('用户管理仅允许具有user:create的学院负责人', async () => {
    userStore.roles = ['UNIT_OWNER']
    userStore.permissions = ['user:create']
    userStore.homePath = '/workbench'
    await router.push('/users')
    expect(router.currentRoute.value.path).toBe('/users')

    userStore.roles = ['LEADER']
    await router.push('/403')
    await router.push('/users')
    expect(router.currentRoute.value.path).toBe('/403')

    userStore.roles = ['UNIT_OWNER']
    userStore.permissions = []
    await router.push('/403')
    await router.push('/users')
    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('学院负责人可进入审核中心，纯干事即使持有异常权限码也被隐藏并拒绝', async () => {
    userStore.roles = ['UNIT_OWNER']
    userStore.permissions = ['submission:review']
    userStore.homePath = '/workbench'
    await router.push('/reviews')
    expect(router.currentRoute.value.path).toBe('/reviews')

    userStore.roles = ['STAFF']
    await router.push('/403')
    await router.push('/reviews')
    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('纯干事根路径进入我的任务工作台', async () => {
    userStore.roles = ['STAFF']
    userStore.homePath = '/workbench'

    await router.push('/')

    expect(router.currentRoute.value.path).toBe('/workbench')
  })
})
