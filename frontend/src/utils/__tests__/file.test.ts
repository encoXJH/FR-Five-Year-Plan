import { describe, expect, it } from 'vitest'
import { DIRECT_UPLOAD_MAX_SIZE, fileCategoryOf, formatFileSize, maxSizeOf } from '../file'

describe('fileCategoryOf 扩展名识别（API 8.5）', () => {
  it('常见格式分类正确且大小写不敏感', () => {
    expect(fileCategoryOf('获奖文件.PDF')).toBe('PDF')
    expect(fileCategoryOf('证书.jpg')).toBe('IMAGE')
    expect(fileCategoryOf('汇总表.xlsx')).toBe('DOCUMENT')
    expect(fileCategoryOf('现场.mp4')).toBe('VIDEO')
    expect(fileCategoryOf('材料.zip')).toBe('ARCHIVE')
  })

  it('不支持的格式返回 null（80001）', () => {
    expect(fileCategoryOf('程序.exe')).toBeNull()
    expect(fileCategoryOf('无扩展名')).toBeNull()
  })
})

describe('maxSizeOf 分类上限', () => {
  it('各类上限与 API 8.5 一致', () => {
    expect(maxSizeOf('IMAGE')).toBe(20 * 1024 * 1024)
    expect(maxSizeOf('PDF')).toBe(50 * 1024 * 1024)
    expect(maxSizeOf('DOCUMENT')).toBe(50 * 1024 * 1024)
    expect(maxSizeOf('VIDEO')).toBe(500 * 1024 * 1024)
    expect(maxSizeOf('ARCHIVE')).toBe(200 * 1024 * 1024)
  })

  it('直传阈值 20MB', () => {
    expect(DIRECT_UPLOAD_MAX_SIZE).toBe(20 * 1024 * 1024)
  })
})

describe('formatFileSize', () => {
  it('按量级显示 B / KB / MB / GB', () => {
    expect(formatFileSize(0)).toBe('0 B')
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(2048)).toBe('2.0 KB')
    expect(formatFileSize(20 * 1024 * 1024)).toBe('20.0 MB')
    expect(formatFileSize(1.5 * 1024 * 1024 * 1024)).toBe('1.5 GB')
  })

  it('非法值显示 —', () => {
    expect(formatFileSize(null)).toBe('—')
    expect(formatFileSize(undefined)).toBe('—')
    expect(formatFileSize(-1)).toBe('—')
  })
})
