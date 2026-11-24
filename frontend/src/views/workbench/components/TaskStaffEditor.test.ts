import { createSSRApp, defineComponent, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it, vi } from 'vitest'
import type { TaskDetail } from '@/types/task'
import TaskStaffEditor from './TaskStaffEditor.vue'

vi.mock('@/api/task', () => ({
  updateTaskStaff: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
  },
}))

const elementTags = ['el-alert', 'el-select', 'el-option', 'el-button', 'el-tag', 'el-empty']

function renderEditor(canAssignStaff: boolean): Promise<string> {
  const task = {
    id: '7',
    version: 3,
    staff: [],
    assignableStaff: [{ id: '301', name: '其他负责人创建的干事' }],
    canAssignStaff,
  } as unknown as TaskDetail
  const app = createSSRApp(TaskStaffEditor, { task })
  for (const tag of elementTags) {
    app.component(tag, defineComponent({
      inheritAttrs: false,
      setup(_, { attrs, slots }) {
        return () => h(tag, attrs, Object.values(slots).map((slot) => slot?.()))
      },
    }))
  }
  return renderToString(app)
}

describe('TaskStaffEditor 同学院协同分配', () => {
  it('可编辑时展示全院干事候选范围和跨创建人候选人', async () => {
    const html = await renderEditor(true)

    expect(html).toContain('同学院负责人均可添加或移除本学院干事')
    expect(html).toContain('全部启用纯干事，不受创建人限制')
    expect(html).toContain('其他负责人创建的干事')
    expect(html).not.toContain('候选人仅包含您创建的')
  })

  it('只读时不再提示仅限任务负责人本人', async () => {
    const html = await renderEditor(false)

    expect(html).toContain('同学院负责人可在可执行任务上分配干事')
    expect(html).not.toContain('任务分配仅限负责人本人')
  })
})
