import { createSSRApp, defineComponent, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it, vi } from 'vitest'
import type { StatCaliber } from '@/types/enums'
import AllocationMatrixEditor from './AllocationMatrixEditor.vue'

vi.mock('@/api/allocation', () => ({
  getAllocation: vi.fn(),
  saveAllocation: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn(),
  },
}))

const elementTags = [
  'el-alert',
  'el-card',
  'el-table',
  'el-tag',
  'el-select',
  'el-option',
  'el-checkbox',
  'el-input-number',
  'el-button',
  'el-dropdown',
  'el-dropdown-menu',
  'el-dropdown-item',
  'el-empty',
]

function registerElementStubs(app: ReturnType<typeof createSSRApp>): void {
  for (const tag of elementTags) {
    app.component(tag, defineComponent({
      inheritAttrs: false,
      setup(_, { attrs, slots }) {
        return () => h(tag, attrs, Object.values(slots).map((slot) => slot?.()))
      },
    }))
  }
  app.component('el-table-column', defineComponent({ render: () => null }))
  app.directive('loading', { getSSRProps: () => ({}) })
}

function renderEditor(statCaliber: StatCaliber): Promise<string> {
  const app = createSSRApp(AllocationMatrixEditor, {
    years: [2026],
    statCaliber,
    yearTargets: [{ year: 2026, target: 10 }],
    totalTarget: 10,
    userOptions: [],
    unitOptions: [],
  })
  registerElementStubs(app)
  return renderToString(app)
}

describe('AllocationMatrixEditor 求和口径', () => {
  it('增长率型显示非求和提示并禁用自动分配', async () => {
    const html = await renderEditor('GROWTH')

    expect(html).toContain('增长率型指标不做目标求和校验，完成值按汇总总量计算增长率')
    expect(html).not.toContain('各年度合计不低于指标目标，可以下发')
    expect(html).not.toContain('合计低于指标目标')
    const autoButton = html.match(/<el-button[^>]*title="增长率[^>]*>/)?.[0]
    expect(autoButton).toBeDefined()
    expect(autoButton).toContain('disabled')
  })

  it('存量型仍保留求和校验与自动分配入口', async () => {
    const html = await renderEditor('STOCK')

    expect(html).toContain('尚未为任何执行单元分解目标')
    expect(html).not.toContain('增长率型指标不做目标求和校验')
    const autoButton = html.match(/<el-button[^>]*title="按指标各年度目标[^>]*>/)?.[0]
    expect(autoButton).toBeDefined()
    expect(autoButton).not.toContain('disabled')
  })
})
