import { createSSRApp, defineComponent, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TrainingStaffDialog from './TrainingStaffDialog.vue'

const { getTrainingStaffMock, updateTrainingStaffMock } = vi.hoisted(() => ({
  getTrainingStaffMock: vi.fn(),
  updateTrainingStaffMock: vi.fn(),
}))

vi.mock('@/api/training', () => ({
  getTrainingStaff: getTrainingStaffMock,
  updateTrainingStaff: updateTrainingStaffMock,
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn() },
}))

const elementTags = ['el-dialog', 'el-alert', 'el-select', 'el-option', 'el-button', 'el-tag', 'el-empty']

function renderDialog(): Promise<string> {
  const app = createSSRApp(TrainingStaffDialog, {
    modelValue: true,
    sheetId: 'sheet-1',
    planName: '2025—2026学年职业培训信息采集',
  })
  for (const tag of elementTags) {
    app.component(tag, defineComponent({
      inheritAttrs: false,
      setup(_, { attrs, slots }) {
        return () => h(tag, attrs, Object.values(slots).map((slot) => slot?.()))
      },
    }))
  }
  app.directive('loading', {
    getSSRProps: (binding) => ({ 'data-loading': String(binding.value) }),
  })
  return renderToString(app)
}

describe('TrainingStaffDialog 培训干事分配', () => {
  beforeEach(() => {
    getTrainingStaffMock.mockReset()
    updateTrainingStaffMock.mockReset()
  })

  it('打开时按 sheetId 调用培训专用分配接口并展示计划', async () => {
    getTrainingStaffMock.mockReturnValue(new Promise(() => undefined))

    const html = await renderDialog()

    expect(getTrainingStaffMock).toHaveBeenCalledWith('sheet-1')
    expect(updateTrainingStaffMock).not.toHaveBeenCalled()
    expect(html).toContain('培训干事分配')
    expect(html).toContain('2025—2026学年职业培训信息采集')
    expect(html).toContain('data-loading="true"')
  })

  it('读取失败时保留重试入口，不调用任务或培训写接口', async () => {
    getTrainingStaffMock.mockImplementation(() => {
      throw new Error('load failed')
    })

    const html = await renderDialog()

    expect(html).toContain('干事分配信息加载失败，请稍后重试')
    expect(html).toContain('重新加载')
    expect(updateTrainingStaffMock).not.toHaveBeenCalled()
  })
})
