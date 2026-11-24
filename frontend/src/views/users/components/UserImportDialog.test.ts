import { createSSRApp, defineComponent, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it, vi } from 'vitest'
import UserImportDialog from './UserImportDialog.vue'
import { confirmUserImport, downloadUserImportTemplate, previewUserImport } from '@/api/system'

vi.mock('@/api/system', () => ({
  confirmUserImport: vi.fn(),
  downloadUserImportTemplate: vi.fn(),
  previewUserImport: vi.fn(),
}))

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ enums: {} }),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
  },
}))

const elementTags = [
  'el-dialog',
  'el-steps',
  'el-step',
  'el-link',
  'el-upload',
  'el-icon',
  'el-button',
  'el-alert',
  'el-table',
  'el-table-column',
  'el-tag',
  'el-form',
  'el-form-item',
  'el-input',
  'el-result',
  'el-tooltip',
]

function renderUploadStep(): Promise<string> {
  const app = createSSRApp(UserImportDialog, {
    modelValue: true,
    staffContext: false,
  })
  for (const tag of elementTags) {
    app.component(tag, defineComponent({
      setup(_, { slots }) {
        return () => h(tag, Object.values(slots).map((slot) => slot?.()))
      },
    }))
  }
  return renderToString(app)
}

describe('UserImportDialog 上传步骤', () => {
  it('展示模板、文件限制和上传入口，统一密码只在后续确认步骤出现', async () => {
    const html = await renderUploadStep()

    expect(html).toContain('批量导入用户')
    expect(html).toContain('下载 Excel 模板')
    expect(html).toContain('支持 .xlsx，文件 ≤ 10MB，单次最多 200 行')
    expect(html).toContain('上传并校验')
    expect(html).not.toContain('统一初始密码')
    expect(html).not.toContain('确认导入')
    expect(downloadUserImportTemplate).not.toHaveBeenCalled()
    expect(previewUserImport).not.toHaveBeenCalled()
    expect(confirmUserImport).not.toHaveBeenCalled()
  })
})
