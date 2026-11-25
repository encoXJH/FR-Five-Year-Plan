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
  it('复用全校人员目录并强制只读', async () => {
    const app = createSSRApp(UsersIndex)
    app.component('el-card', { template: '<section><slot /></section>' })

    const html = await renderToString(app)

    expect(html).toContain('用户管理')
    expect(html).toContain('查询全校人员及其所属学院、角色和账号状态')
    expect(html).toContain('只读=true')
    expect(html).toContain('学院角色限制=true')
  })
})
