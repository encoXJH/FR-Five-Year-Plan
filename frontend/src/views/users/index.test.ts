import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it, vi } from 'vitest'
import UsersIndex from './index.vue'

vi.mock('@/views/system/components/UserPanel.vue', () => ({
  default: {
    props: { readonly: Boolean, unitOwnerDirectory: Boolean },
    template: '<div class="directory-panel">只读={{ readonly }}；学院角色限制={{ unitOwnerDirectory }}</div>',
  },
}))

describe('独立用户管理页面', () => {
  it('复用全校人员目录并开放本学院干事与本人创建负责人管理', async () => {
    const app = createSSRApp(UsersIndex)
    app.component('el-card', { template: '<section><slot /></section>' })

    const html = await renderToString(app)

    expect(html).toContain('用户管理')
    expect(html).toContain('本学院任意负责人归属的干事及本人创建的学院项目负责人可编辑、删除及转换角色')
    expect(html).toContain('只读=false')
    expect(html).toContain('学院角色限制=true')
  })
})
