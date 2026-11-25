import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserPanel from './UserPanel.vue'

let roles: string[] = []

vi.mock('@/api/system', () => ({
  deleteUser: vi.fn(),
  listUsers: vi.fn(),
  resetUserPassword: vi.fn(),
  setUserRoles: vi.fn(),
  updateUser: vi.fn(),
}))

vi.mock('@/api/orgUnit', () => ({
  listOrgUnits: vi.fn(),
}))

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ roles, enums: {} }),
}))

vi.mock('@/utils/autoRefresh', () => ({
  useAutoRefresh: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

async function renderPanel(readonly = false, unitOwnerDirectory = false): Promise<string> {
  const app = createSSRApp(UserPanel, { readonly, unitOwnerDirectory })
  app.directive('loading', { getSSRProps: () => ({}) })
  app.component('el-alert', {
    props: ['title', 'description'],
    template: '<div class="directory-alert"><strong>{{ title }}</strong><span>{{ description }}</span></div>',
  })
  app.component('el-table-column', {
    props: ['label'],
    template: '<span class="user-column">{{ label }}</span>',
  })
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
  for (const name of [
    'el-input', 'el-select', 'el-tag', 'el-table', 'el-pagination',
    'el-form', 'el-form-item', 'el-switch', 'el-dropdown', 'el-dropdown-menu', 'el-dropdown-item',
  ]) {
    app.component(name, { template: '<div><slot /></div>' })
  }
  return renderToString(app)
}

describe('系统管理用户目录', () => {
  beforeEach(() => {
    roles = ['LEADER']
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

  it('独立用户管理即使包含管理员角色也强制只读', async () => {
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
})
