import type { FileCategory } from '@/types/enums'
import { EMPTY_DISPLAY } from './format'

/** 直接上传上限：≤ 20MB 走 POST /files，超过走分片（API 约定 8.1） */
export const DIRECT_UPLOAD_MAX_SIZE = 20 * 1024 * 1024

export interface FileCategoryRule {
  category: FileCategory
  extensions: string[]
  /** 单文件上限（字节，API 8.5 默认值，系统参数可改） */
  maxSize: number
}

/** 文件分类与大小上限（API 约定 8.5；前端预校验，服务端兜底 80001 / 80002） */
export const FILE_CATEGORY_RULES: FileCategoryRule[] = [
  { category: 'DOCUMENT', extensions: ['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt'], maxSize: 50 * 1024 * 1024 },
  { category: 'PDF', extensions: ['pdf'], maxSize: 50 * 1024 * 1024 },
  { category: 'IMAGE', extensions: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp'], maxSize: 20 * 1024 * 1024 },
  { category: 'VIDEO', extensions: ['mp4', 'mov', 'avi', 'wmv'], maxSize: 500 * 1024 * 1024 },
  { category: 'ARCHIVE', extensions: ['zip', 'rar'], maxSize: 200 * 1024 * 1024 },
]

/** 按扩展名识别文件分类；不支持的格式返回 null（80001） */
export function fileCategoryOf(fileName: string): FileCategory | null {
  const ext = fileName.split('.').pop()?.toLowerCase() ?? ''
  if (!ext) return null
  const rule = FILE_CATEGORY_RULES.find((item) => item.extensions.includes(ext))
  return rule ? rule.category : null
}

export function maxSizeOf(category: FileCategory): number {
  return FILE_CATEGORY_RULES.find((item) => item.category === category)?.maxSize ?? DIRECT_UPLOAD_MAX_SIZE
}

/** 文件大小显示：B / KB / MB / GB，非 B 保留 1 位小数 */
export function formatFileSize(bytes: number | null | undefined): string {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return EMPTY_DISPLAY
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB'] as const
  let size = bytes / 1024
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index += 1
  }
  return `${size.toFixed(1)} ${units[index]}`
}

/** 文件分类图标颜色（UI 规范 7.6） */
export const FILE_CATEGORY_COLORS: Record<FileCategory, string> = {
  DOCUMENT: '#2B5BC0',
  PDF: '#F56C6C',
  IMAGE: '#67C23A',
  VIDEO: '#8E5CD9',
  ARCHIVE: '#909399',
}
