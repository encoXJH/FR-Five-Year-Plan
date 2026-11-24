import type { UserImportPreviewResult } from '@/types/system'

export const USER_IMPORT_MAX_FILE_SIZE = 10 * 1024 * 1024
export const USER_IMPORT_MAX_ROWS = 200

export function validateUserImportFile(file: Pick<File, 'name' | 'size'>): string | null {
  if (!file.name.toLowerCase().endsWith('.xlsx')) {
    return '仅支持 .xlsx 格式文件'
  }
  if (file.size > USER_IMPORT_MAX_FILE_SIZE) {
    return '文件不能超过 10MB'
  }
  return null
}

export function validateUserImportPassword(password: string): string | null {
  if (!password) return '请输入统一初始密码'
  if (password.length < 8 || password.length > 64) return '统一初始密码长度须为 8–64 位'
  return null
}

export function userImportPreviewError(
  preview: Pick<UserImportPreviewResult, 'totalRows' | 'invalidRows'>,
): string | null {
  if (preview.totalRows > USER_IMPORT_MAX_ROWS) {
    return `共 ${preview.totalRows} 行，超过单次 ${USER_IMPORT_MAX_ROWS} 行上限，请拆分后重新上传`
  }
  if (preview.invalidRows > 0) {
    return `共 ${preview.totalRows} 行，${preview.invalidRows} 行存在错误，修正后请重新上传`
  }
  return null
}
