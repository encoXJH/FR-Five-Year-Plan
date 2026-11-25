import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DirectionPanel from './DirectionPanel.vue'

let roles: string[] = []
let permissions: string[] = []

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({
    roles,
    can: (...codes: string[]) => codes.some((code) => permissions.includes(code)),
  }),
}))

vi.mock('@/utils/autoRefresh', () => ({ useAutoRefresh: vi.fn() }))

async function renderPanel(): Promise<string> {
  const app = createSSRApp(DirectionPanel)
  app.directive('loading', { getSSRProps: () => ({}) })
  app.component('el-alert', {
    props: ['title'],
    template: '<div class="direction-alert">{{ title }}</div>',
  })
  app.component('el-button', { template: '<button><slot /></button>' })
  for (const name of [
    'el-select', 'el-option', 'el-card', 'el-table', 'el-table-column', 'el-empty',
    'el-dialog', 'el-form', 'el-form-item', 'el-input', 'el-input-number',
  ]) {
    app.component(name, { template: '<div><slot /></div>' })
  }
  return renderToString(app)
}

describe('主要建设方向配置权限', () => {
  beforeEach(() => {
    permissions = ['system:manage']
  })

  it('校级负责人显示新增方向和总负责人配置入口，不再显示无权限提示', async () => {
    roles = ['LEADER']
    const html = await renderPanel()

    expect(html).toContain('新增 主要建设方向')
    expect(html).toContain('仅可选择 LEADER 角色用户')
    expect(html).not.toContain('仅平台管理员或校级负责人可配置')
  })

  it('没有管理角色的账号仍只显示无权限提示', async () => {
    roles = ['SCHOOL_OWNER']
    permissions = []
    const html = await renderPanel()

    expect(html).toContain('仅平台管理员或校级负责人可配置主要建设方向')
    expect(html).not.toContain('新增 主要建设方向')
  })
})
