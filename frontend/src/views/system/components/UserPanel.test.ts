import { createSSRApp, defineComponent, getCurrentInstance, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserPanel from './UserPanel.vue'
import {
  deleteManagedUser,
  deleteUser,
  listUsers,
  resetStaffUserPassword,
  setManagedUserRoles,
  setUserRoles,
  updateStaffUser,
  updateUser,
} from '@/api/system'
import { listOrgUnits } from '@/api/orgUnit'
import type { Role } from '@/types/enums'
import type { SystemUser } from '@/types/system'

let roles: Role[] = []

vi.mock('@/api/system', () => ({
  deleteManagedUser: vi.fn(),
  deleteUser: vi.fn(),
  listUsers: vi.fn(),
  resetStaffUserPassword: vi.fn(),
  resetUserPassword: vi.fn(),
  setManagedUserRoles: vi.fn(),
  setUserRoles: vi.fn(),
  updateStaffUser: vi.fn(),
  updateUser: vi.fn(),
}))

vi.mock('@/api/orgUnit', () => ({
  listOrgUnits: vi.fn(),
}))

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({
    roles,
    enums: {},
    userInfo: { unitId: '101', unitIds: ['101'], units: [] },
    dataScope: { unitId: '101', unitIds: ['101'] },
  }),
}))

vi.mock('@/utils/autoRefresh', () => ({
  useAutoRefresh: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() },
}))

interface PanelState {
  rolesForm: { roles: Role[] }
  form: { name: string; phone: string; unitId: string; isEnabled: boolean; role: Role | ''; roleReason: string }
  openSetRoles: (row: SystemUser) => void
  submitRoles: (reason: string) => Promise<void>
  openEdit: (row: SystemUser) => void
  submit: () => Promise<void>
  openDelete: (row: SystemUser) => void
  submitDelete: (reason: string) => Promise<void>
  toggleEnabled: (row: SystemUser) => Promise<void>
  handleResetPassword: (row: SystemUser) => Promise<void>
}

const managedRow: SystemUser = {
  id: '1',
  username: 'owner001',
  name: '本人创建账号',
  unitId: '101',
  unitName: '智能产业学院',
  unitIds: ['101'],
  roles: ['UNIT_OWNER'],
  isEnabled: true,
  version: 3,
  createdBy: 'manager-1',
  manageable: true,
}

const listUsersMock = listUsers as unknown as ReturnType<typeof vi.fn>
const listOrgUnitsMock = listOrgUnits as unknown as ReturnType<typeof vi.fn>
const setManagedRolesMock = setManagedUserRoles as unknown as ReturnType<typeof vi.fn>
const setRolesMock = setUserRoles as unknown as ReturnType<typeof vi.fn>
const updateManagedMock = updateStaffUser as unknown as ReturnType<typeof vi.fn>
const updateMock = updateUser as unknown as ReturnType<typeof vi.fn>
const deleteManagedMock = deleteManagedUser as unknown as ReturnType<typeof vi.fn>
const deleteMock = deleteUser as unknown as ReturnType<typeof vi.fn>
const resetManagedMock = resetStaffUserPassword as unknown as ReturnType<typeof vi.fn>
const promptMock = ElMessageBox.prompt as unknown as ReturnType<typeof vi.fn>
const confirmMock = ElMessageBox.confirm as unknown as ReturnType<typeof vi.fn>

async function renderPanelDetailed(
  readonly = false,
  unitOwnerDirectory = false,
  row?: SystemUser,
): Promise<{ html: string; state?: PanelState }> {
  const app = createSSRApp(UserPanel, { readonly, unitOwnerDirectory })
  let capturedState: PanelState | undefined
  app.directive('loading', { getSSRProps: () => ({}) })
  app.component('el-alert', defineComponent({
    props: ['title', 'description'],
    setup(props) {
      const parent = getCurrentInstance()?.parent as unknown as { setupState?: PanelState } | undefined
      if (typeof parent?.setupState?.openSetRoles === 'function') capturedState = parent.setupState
      return () => h('div', { class: 'directory-alert' }, [
        h('strong', String(props.title)), h('span', String(props.description)),
      ])
    },
  }))
  app.component('el-table-column', defineComponent({
    props: ['label'],
    setup(props, { slots }) {
      return () => h('span', [
        h('span', { class: 'user-column' }, String(props.label)),
        row ? slots.default?.({ row }) : null,
      ])
    },
  }))
  app.component('el-dialog', {
    props: ['title'],
    template: '<div class="user-dialog">{{ title }}<slot /></div>',
  })
  app.component('el-option', {
    props: ['label'],
    template: '<span>{{ label }}</span>',
  })
  app.component('ReasonDialog', {
    props: ['title'],
    template: '<div class="reason-dialog">{{ title }}</div>',
  })
  app.component('el-button', { template: '<button><slot /></button>' })
  app.component('el-dropdown', { template: '<div><slot /><slot name="dropdown" /></div>' })
  for (const name of [
    'el-input', 'el-select', 'el-tag', 'el-table', 'el-pagination',
    'el-form', 'el-form-item', 'el-switch', 'el-dropdown-menu', 'el-dropdown-item',
  ]) {
    app.component(name, { template: '<div><slot /></div>' })
  }
  const html = await renderToString(app)
  return { html, state: capturedState }
}

async function renderPanel(readonly = false, unitOwnerDirectory = false): Promise<string> {
  return (await renderPanelDetailed(readonly, unitOwnerDirectory)).html
}

describe('系统管理用户目录', () => {
  beforeEach(() => {
    roles = ['LEADER']
    listUsersMock.mockReset()
    listOrgUnitsMock.mockReset()
    setManagedRolesMock.mockReset()
    setRolesMock.mockReset()
    updateManagedMock.mockReset()
    updateMock.mockReset()
    deleteManagedMock.mockReset()
    deleteMock.mockReset()
    resetManagedMock.mockReset()
    promptMock.mockReset()
    confirmMock.mockReset()
    listUsersMock.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
    listOrgUnitsMock.mockResolvedValue([{ id: '101', code: 'C01', name: '智能产业学院' }])
    setManagedRolesMock.mockResolvedValue({ user: managedRow })
    setRolesMock.mockResolvedValue({ user: managedRow })
    updateManagedMock.mockResolvedValue(managedRow)
    updateMock.mockResolvedValue(managedRow)
    deleteManagedMock.mockResolvedValue(undefined)
    deleteMock.mockResolvedValue(undefined)
    resetManagedMock.mockResolvedValue(undefined)
    promptMock.mockResolvedValue({ value: 'newpassword123' })
    confirmMock.mockResolvedValue(undefined)
  })

  it('校级负责人可筛选全校人员但没有新增、编辑、角色、密码或删除入口', async () => {
    const html = await renderPanel()

    expect(html).toContain('全校人员查询')
    expect(html).toContain('姓名 / 工号')
    expect(html).toContain('所属单元')
    expect(html).toContain('全部角色')
    expect(html).toContain('全部状态')
    expect(html).toContain('查询<!--]--></button>')
    for (const text of ['新增用户', '编辑用户', '设置角色', '重置密码', '删除用户']) {
      expect(html).not.toContain(text)
    }
    expect(html).not.toContain('>操作</span>')
  })

  it('平台管理员保留原有人员管理功能且不显示只读提示', async () => {
    roles = ['ADMIN']
    const html = await renderPanel()

    expect(html).not.toContain('全校人员查询')
    for (const text of ['新增用户', '编辑用户', '设置角色', '删除用户']) {
      expect(html).toContain(text)
    }
    expect(html).toContain('>操作</span>')
  })

  it('显式只读目录即使包含管理员角色也不开放管理', async () => {
    roles = ['ADMIN', 'UNIT_OWNER']
    const html = await renderPanel(true)

    expect(html).toContain('全校人员查询')
    expect(html).not.toContain('新增用户')
    expect(html).not.toContain('编辑用户')
    expect(html).not.toContain('>操作</span>')
  })

  it('学院负责人角色筛选只显示学院项目负责人和干事', async () => {
    roles = ['UNIT_OWNER']
    const html = await renderPanel(true, true)

    expect(html).toContain('学院项目负责人')
    expect(html).toContain('干事')
    expect(html).not.toContain('平台管理员')
    expect(html).not.toContain('校级负责人')
    expect(html).not.toContain('建设方向负责人')
  })

  it('本人创建账号显示角色编辑和管理操作，他人创建账号保持只读', async () => {
    roles = ['UNIT_OWNER']
    const own = await renderPanelDetailed(false, true, managedRow)
    expect(own.html).toContain('学院账号管理')
    expect(own.html).not.toContain('role-edit-button')
    expect(own.html).toContain('编辑')
    expect(own.html).toContain('重置密码')

    const other = await renderPanelDetailed(false, true, { ...managedRow, manageable: false })
    expect(other.html).toContain('只读')
    expect(other.html).not.toContain('role-edit-button')
    expect(other.html).not.toContain('重置密码')
  })

  it('操作列编辑在同一请求提交资料和角色调整原因', async () => {
    roles = ['UNIT_OWNER']
    const { state } = await renderPanelDetailed(false, true, managedRow)
    if (!state) throw new Error('未捕获用户面板状态')
    state.openEdit(managedRow)
    state.form.role = 'STAFF'
    state.form.roleReason = '调整为干事'

    await state.submit()

    expect(updateManagedMock).toHaveBeenCalledWith('1', expect.objectContaining({
      role: 'STAFF', reason: '调整为干事', unitId: '101', version: 3,
    }))
    expect(setManagedRolesMock).not.toHaveBeenCalled()
    expect(setRolesMock).not.toHaveBeenCalled()
    expect(updateMock).not.toHaveBeenCalled()
  })

  it('资料编辑和删除调用学院专用接口，不调用管理员接口', async () => {
    roles = ['UNIT_OWNER']
    const { state } = await renderPanelDetailed(false, true, managedRow)
    if (!state) throw new Error('未捕获用户面板状态')
    state.openEdit(managedRow)
    state.form.phone = '13812345678'
    await state.submit()
    state.openDelete(managedRow)
    await state.submitDelete('误建账号')

    expect(updateManagedMock).toHaveBeenCalledWith('1', expect.objectContaining({
      name: '本人创建账号', phone: '13812345678', unitId: '101', version: 3,
    }))
    expect(deleteManagedMock).toHaveBeenCalledWith('1', { version: 3, reason: '误建账号' })
    expect(updateMock).not.toHaveBeenCalled()
    expect(deleteMock).not.toHaveBeenCalled()
  })

  it('启停和重置密码沿用学院专用接口及版本校验', async () => {
    roles = ['UNIT_OWNER']
    const disabledRow = { ...managedRow, isEnabled: false }
    const { state } = await renderPanelDetailed(false, true, disabledRow)
    if (!state) throw new Error('未捕获用户面板状态')

    await state.toggleEnabled(disabledRow)
    await state.handleResetPassword(disabledRow)

    expect(updateManagedMock).toHaveBeenCalledWith('1', { isEnabled: true, version: 3 })
    expect(resetManagedMock).toHaveBeenCalledWith('1', { password: 'newpassword123', version: 3 })
  })
})
