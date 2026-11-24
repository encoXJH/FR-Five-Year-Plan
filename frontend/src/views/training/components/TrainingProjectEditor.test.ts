import { createSSRApp, defineComponent, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it, vi } from 'vitest'
import TrainingProjectEditor from './TrainingProjectEditor.vue'
import type { TrainingField, TrainingFieldType } from '@/types/training'

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn() },
}))

const elementTags = [
  'el-drawer',
  'el-alert',
  'el-form',
  'el-form-item',
  'el-input',
  'el-input-number',
  'el-select',
  'el-option',
  'el-date-picker',
  'el-button',
]

const groupDefinitions = [
  { group: 'BASIC', groupLabel: '项目基本', from: 1, to: 6 },
  { group: 'FUNDING', groupLabel: '合同经费', from: 7, to: 11 },
  { group: 'IMPLEMENTATION', groupLabel: '培训实施', from: 12, to: 17 },
  { group: 'AUDIENCE', groupLabel: '培训对象', from: 18, to: 32 },
  { group: 'INDUSTRY', groupLabel: '重点行业', from: 33, to: 44 },
  { group: 'REGION', groupLabel: '区域战略', from: 45, to: 54 },
  { group: 'TEACHER', groupLabel: '教师填报', from: 55, to: 57 },
]

const fields: TrainingField[] = groupDefinitions.flatMap((group) =>
  Array.from({ length: group.to - group.from + 1 }, (_, offset) => {
    const column = group.from + offset
    const key = `c${String(column).padStart(2, '0')}`
    let type: TrainingFieldType = 'TEXT'
    if (key === 'c02') type = 'SELECT'
    if (key === 'c05' || key === 'c06') type = 'DATE'
    if (key === 'c09' || key === 'c10' || key === 'c11' || key === 'c12') type = 'DECIMAL'
    if ((column >= 14 && column <= 54) && !['c15', 'c17'].includes(key)) type = 'INTEGER'
    return {
      key,
      column,
      label: `${group.groupLabel}字段${key}`,
      group: group.group,
      groupLabel: group.groupLabel,
      type,
      required: column <= 6,
      options: key === 'c02' ? ['是', '否'] : [],
    }
  }),
)

function renderEditor(readonly = false): Promise<string> {
  const app = createSSRApp(TrainingProjectEditor, {
    modelValue: true,
    fields,
    readonly,
    project: {
      id: 'project-1',
      rowNo: 1,
      data: { c10: 1.25, c11: 2.75, c18: 2, c19: 3, c33: 4, c45: 5 },
    },
  })
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

describe('TrainingProjectEditor schema 渲染', () => {
  it('按 schema 渲染七组字段而非 57 列表格，并展示只读合计', async () => {
    const html = await renderEditor()

    for (const group of groupDefinitions) expect(html).toContain(group.groupLabel)
    expect(html).toContain('项目基本字段c01')
    expect(html).toContain('教师填报字段c57')
    expect(html).not.toContain('<el-table')
    expect(html).toContain('到账经费（万元，财政资金 + 非财政资金）')
    expect(html).toMatch(/<strong[^>]*>4<\/strong>/)
    expect(html).toContain('保存后由系统填写')
    expect((html.match(/readonly/g) ?? []).length).toBeGreaterThanOrEqual(4)
  })

  it('待审或已通过项目显示只读说明，不显示草稿提示', async () => {
    const html = await renderEditor(true)

    expect(html).toContain('该项目正在审核或已审核通过，内容只读')
    expect(html).not.toContain('草稿允许暂缺必填项')
  })
})
