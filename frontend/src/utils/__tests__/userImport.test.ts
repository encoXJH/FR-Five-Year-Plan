import { describe, expect, it } from 'vitest'
import {
  USER_IMPORT_MAX_FILE_SIZE,
  userImportPreviewError,
  validateUserImportFile,
  validateUserImportPassword,
} from '../userImport'

describe('用户批量导入文件校验', () => {
  it('接受扩展名不区分大小写的 xlsx 文件及 10MB 边界', () => {
    expect(validateUserImportFile({ name: '用户.xlsx', size: USER_IMPORT_MAX_FILE_SIZE })).toBeNull()
    expect(validateUserImportFile({ name: '用户.XLSX', size: 1 })).toBeNull()
  })

  it('在上传前拒绝非 xlsx 文件和超过 10MB 的文件', () => {
    expect(validateUserImportFile({ name: '用户.xls', size: 1 })).toBe('仅支持 .xlsx 格式文件')
    expect(validateUserImportFile({ name: '用户.xlsx.exe', size: 1 })).toBe('仅支持 .xlsx 格式文件')
    expect(validateUserImportFile({ name: '用户.xlsx', size: USER_IMPORT_MAX_FILE_SIZE + 1 }))
      .toBe('文件不能超过 10MB')
  })
})

describe('用户批量导入确认校验', () => {
  it('统一初始密码仅接受 8–64 位', () => {
    expect(validateUserImportPassword('')).toBe('请输入统一初始密码')
    expect(validateUserImportPassword('a'.repeat(7))).toBe('统一初始密码长度须为 8–64 位')
    expect(validateUserImportPassword('a'.repeat(8))).toBeNull()
    expect(validateUserImportPassword('a'.repeat(64))).toBeNull()
    expect(validateUserImportPassword('a'.repeat(65))).toBe('统一初始密码长度须为 8–64 位')
  })

  it('存在错误行或超过 200 行时阻止确认', () => {
    expect(userImportPreviewError({ totalRows: 2, invalidRows: 1 }))
      .toBe('共 2 行，1 行存在错误，修正后请重新上传')
    expect(userImportPreviewError({ totalRows: 201, invalidRows: 0 }))
      .toBe('共 201 行，超过单次 200 行上限，请拆分后重新上传')
    expect(userImportPreviewError({ totalRows: 200, invalidRows: 0 })).toBeNull()
  })
})
