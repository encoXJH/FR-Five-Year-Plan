import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  confirmUserImport,
  downloadUserImportTemplate,
  listStaffUsers,
  previewUserImport,
  resetStaffUserPassword,
  updateStaffUser,
} from '../system'
import { http } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn(), patch: vi.fn(), post: vi.fn() },
}))

const getMock = http.get as unknown as ReturnType<typeof vi.fn>
const patchMock = http.patch as unknown as ReturnType<typeof vi.fn>
const postMock = http.post as unknown as ReturnType<typeof vi.fn>

describe('本学院干事用户管理 API', () => {
  beforeEach(() => {
    getMock.mockReset()
    patchMock.mockReset()
    postMock.mockReset()
  })

  it('按学院、状态和分页查询纯干事', async () => {
    getMock.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
    await listStaffUsers({ unitId: '101', keyword: '张', isEnabled: true, page: 1, pageSize: 20 })
    expect(getMock).toHaveBeenCalledWith('/users/staff', {
      unitId: '101', keyword: '张', isEnabled: true, page: 1, pageSize: 20,
    })
  })

  it('编辑和重置密码均携带版本号', async () => {
    patchMock.mockResolvedValue({ id: '1' })
    postMock.mockResolvedValue(undefined)
    await updateStaffUser('1', { name: '干事', unitId: '101', version: 2 })
    await resetStaffUserPassword('1', { password: 'NewPassword@2026', version: 3 })
    expect(patchMock).toHaveBeenCalledWith('/users/staff/1', { name: '干事', unitId: '101', version: 2 })
    expect(postMock).toHaveBeenCalledWith('/users/staff/1/password-reset', {
      password: 'NewPassword@2026', version: 3,
    })
  })

  it('下载用户导入模板时请求 blob 文件流', async () => {
    const blob = new Blob(['template'])
    getMock.mockResolvedValue(blob)

    await expect(downloadUserImportTemplate()).resolves.toBe(blob)
    expect(getMock).toHaveBeenCalledWith('/users/import-template', undefined, {
      responseType: 'blob',
    })
  })

  it('预览接口仅以 multipart 的 file 字段上传 Excel', async () => {
    const response = {
      importId: 'import-1',
      totalRows: 1,
      validRows: 1,
      invalidRows: 0,
      rows: [{
        rowNum: 2,
        name: '张三',
        username: 'zhangsan',
        phone: '13800000000',
        roles: ['STAFF'],
        primaryUnitCode: 'C01',
        otherUnitCodes: [],
        valid: true,
        errors: [],
      }],
    }
    postMock.mockResolvedValue(response)
    const file = new File(['excel'], '用户.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })

    await expect(previewUserImport(file)).resolves.toBe(response)
    expect(postMock).toHaveBeenCalledWith('/users/import-preview', expect.any(FormData))
    const body = postMock.mock.calls[0][1] as FormData
    expect(body.get('file')).toBe(file)
    expect([...body.keys()]).toEqual(['file'])
  })

  it('确认接口提交统一初始密码并携带指定幂等键', async () => {
    const response = { createdCount: 2, userIds: ['1', '2'] }
    postMock.mockResolvedValue(response)
    const request = { importId: 'import-1', initialPassword: 'Password@2026' }

    await expect(confirmUserImport(request, 'same-import-key')).resolves.toBe(response)
    expect(postMock).toHaveBeenCalledWith('/users/import-confirm', request, {
      headers: { 'Idempotency-Key': 'same-import-key' },
    })
  })
})
