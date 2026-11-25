import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SystemIndex from './index.vue'

let roles: string[] = []

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({
    roles,
    can: (...codes: string[]) => codes.includes('system:manage'),
  }),
}))

async function renderPage(): Promise<string> {
  const app = createSSRApp(SystemIndex)
  app.component('el-card', { template: '<div><slot /></div>' })
  app.component('el-tabs', { template: '<div><slot /></div>' })
  app.component('el-tab-pane', {
    props: ['label'],
    template: '<span class="system-tab">{{ label }}</span>',
  })
  for (const name of [
    'OrgUnitPanel', 'OrgStructurePanel', 'DirectionPanel', 'MajorGroupPanel', 'UserPanel',
    'RolePermissionPanel', 'DictPanel', 'SettingsPanel', 'FeishuPanel', 'FeishuBotPanel',
  ]) {
    app.component(name, { template: '<div />' })
  }
  return renderToString(app)
}

describe('系统管理页签按角色显示', () => {
  beforeEach(() => {
    roles = ['LEADER']
  })

  it('校级负责人只显示执行单元、组织架构、主要建设方向、专业群、用户与角色', async () => {
    const html = await renderPage()

    for (const label of ['执行单元', '组织架构', '主要建设方向', '专业群', '用户与角色']) {
      expect(html).toContain(`>${label}</span>`)
    }
    expect((html.match(/class="system-tab"/g) ?? []).length).toBe(5)
    for (const label of ['角色权限设置', '字典', '参数配置', '飞书集成', '双高管理平台机器人']) {
      expect(html).not.toContain(`>${label}</span>`)
    }
  })

  it('平台管理员继续显示全部系统管理页签', async () => {
    roles = ['ADMIN']
    const html = await renderPage()

    expect((html.match(/class="system-tab"/g) ?? []).length).toBe(10)
    expect(html).toContain('角色权限设置')
    expect(html).toContain('双高管理平台机器人')
  })
})
