import { createSSRApp, defineComponent, getCurrentInstance, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserCreate from './create.vue'
import { createUser, listUsers } from '@/api/system'
import { listOrgUnits } from '@/api/orgUnit'
import type { Role } from '@/types/enums'
import type { SystemUser } from '@/types/system'

vi.mock('@/api/system', () => ({
  createUser: vi.fn(),
  downloadUserImportTemplate: vi.fn(),
  listUsers: vi.fn(),
}))

vi.mock('@/api/orgUnit', () => ({
  listOrgUnits: vi.fn(),
}))

vi.mock('@/components/BackButton.vue', () => ({
  default: { template: '<div />' },
}))

vi.mock('@/views/users/components/UserImportDialog.vue', () => ({
  default: { template: '<div />' },
}))

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({
    roles: currentRoles,
    enums: {},
    userInfo: {
      unitId: 'unit-1',
      unitIds: ['unit-1'],
      units: [{ id: 'unit-1', code: 'C01', name: '智能产业学院' }],
    },
    dataScope: null,
    can: () => true,
    homePath: '/plans',
  }),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn() },
}))

let currentRoles: Role[] = ['ADMIN']
const createUserMock = createUser as unknown as ReturnType<typeof vi.fn>
const listUsersMock = listUsers as unknown as ReturnType<typeof vi.fn>
const listOrgUnitsMock = listOrgUnits as unknown as ReturnType<typeof vi.fn>

interface CreatePageState {
  form: {
    name: string
    username: string
    password: string
    phone: string
    roles: Role[]
    unitIds: string[]
    ownerUserId: string
  }
  formRef: { validate: () => Promise<boolean> } | null
  ownerOptions: SystemUser[]
  adminStaffCreation: boolean
  handleRolesChange: (selected: Role[]) => void
  loadOwnerOptions: () => Promise<void>
  ownerOptionLabel: (owner: SystemUser) => string
  submit: () => Promise<void>
}

const owner: SystemUser = {
  id: 'owner-1',
  username: '990501',
  name: '学院负责人',
  unitId: 'unit-1',
  unitName: '智能产业学院',
  unitIds: ['unit-1'],
  roles: ['UNIT_OWNER'],
  isEnabled: true,
  version: 0,
}

const elementTags = [
  'el-button', 'el-alert', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option',
]

async function renderPage(roles: Role[]): Promise<{ html: string; state: CreatePageState }> {
  currentRoles = roles
  const app = createSSRApp(UserCreate)
  let capturedState: CreatePageState | undefined
  for (const tag of elementTags) {
    app.component(tag, defineComponent({
      inheritAttrs: false,
      setup(_, { attrs, slots }) {
        return () => h(tag, attrs, Object.values(slots).map((slot) => slot?.()))
      },
    }))
  }
  app.component('el-card', defineComponent({
    setup(_, { slots }) {
      const parent = getCurrentInstance()?.parent as unknown as {
        setupState?: Partial<CreatePageState>
      } | undefined
      if (typeof parent?.setupState?.submit === 'function') {
        capturedState = parent.setupState as CreatePageState
      }
      return () => h('el-card', Object.values(slots).map((slot) => slot?.()))
    },
  }))
  app.directive('loading', { getSSRProps: () => ({}) })
  const html = await renderToString(app)
  if (!capturedState) throw new Error('未捕获用户添加页状态')
  return { html, state: capturedState }
}

function prepareSubmit(state: CreatePageState): void {
  state.form.name = '测试用户'
  state.form.username = 'test_user'
  state.form.password = 'password123'
  state.formRef = { validate: vi.fn().mockResolvedValue(true) }
}

describe('用户添加页角色与干事归属', () => {
  beforeEach(() => {
    createUserMock.mockReset()
    listUsersMock.mockReset()
    listOrgUnitsMock.mockReset()
    createUserMock.mockResolvedValue({})
    listOrgUnitsMock.mockResolvedValue([])
    listUsersMock.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 100 })
  })

  it('ADMIN 角色多选控件包含干事，管理角色仍按数组多选提交', async () => {
    const { html, state } = await renderPage(['ADMIN'])

    expect(html).toContain('multiple')
    expect(html).toContain('value="STAFF"')

    prepareSubmit(state)
    state.handleRolesChange(['LEADER', 'SCHOOL_OWNER'])
    await state.submit()

    expect(createUserMock).toHaveBeenCalledWith(expect.objectContaining({
      roles: ['LEADER', 'SCHOOL_OWNER'],
      ownerUserId: undefined,
    }))
  })

  it('ADMIN 创建干事时加载归属负责人并提交空学院参数', async () => {
    listUsersMock.mockResolvedValue({ items: [owner], total: 1, page: 1, pageSize: 100 })
    const { state } = await renderPage(['ADMIN'])
    prepareSubmit(state)
    state.handleRolesChange(['STAFF'])
    await state.loadOwnerOptions()
    state.form.ownerUserId = owner.id

    expect(state.adminStaffCreation).toBe(true)
    expect(state.ownerOptions).toEqual([owner])
    expect(state.ownerOptionLabel(owner)).toContain('学院负责人（990501）')
    expect(state.ownerOptionLabel(owner)).toContain('智能产业学院')

    await state.submit()

    expect(listUsersMock).toHaveBeenCalledWith({
      role: 'UNIT_OWNER', isEnabled: true, page: 1, pageSize: 100,
    })
    expect(createUserMock).toHaveBeenCalledWith(expect.objectContaining({
      roles: ['STAFF'],
      ownerUserId: 'owner-1',
      unitId: null,
      unitIds: [],
    }))
  })

  it('负责人候选超过一页时继续加载全部候选', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({
      ...owner,
      id: `owner-${index + 2}`,
      username: `owner-${index + 2}`,
    }))
    listUsersMock.mockImplementation(({ page }: { page: number }) => Promise.resolve({
      items: page === 1 ? firstPage : [owner],
      total: 101,
      page,
      pageSize: 100,
    }))
    const { state } = await renderPage(['ADMIN'])

    await state.loadOwnerOptions()

    expect(listUsersMock).toHaveBeenCalledTimes(2)
    expect(state.ownerOptions).toHaveLength(101)
  })

  it('UNIT_OWNER 创建干事沿用原归属，不加载或提交其他负责人', async () => {
    const { state } = await renderPage(['UNIT_OWNER'])
    prepareSubmit(state)
    state.handleRolesChange(['STAFF'])

    expect(state.adminStaffCreation).toBe(false)
    await state.submit()

    expect(listUsersMock).not.toHaveBeenCalled()
    expect(createUserMock).toHaveBeenCalledWith(expect.objectContaining({
      roles: ['STAFF'],
      ownerUserId: undefined,
      unitId: null,
      unitIds: [],
    }))
  })
})
