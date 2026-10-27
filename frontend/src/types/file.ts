import type { UserRef } from './api'
import type { FileBizType, FileCategory, PreviewType, TranscodeStatus } from './enums'

/** 文件对象 FileInfo（API 约定 8.3） */
export interface FileInfo {
  id: string
  fileName: string
  size: number
  mimeType: string | null
  category: FileCategory
  bizType: FileBizType
  status: 'TEMP' | 'BOUND'
  transcodeStatus: TranscodeStatus | null
  uploadedAt: string
  uploadedBy: UserRef | null
}

/** GET /files/{fileId}/preview 返回（API 8.6，url 为 10 分钟有效的签名地址） */
export interface PreviewInfo {
  previewType: PreviewType
  url: string | null
  expiresAt: string | null
}

/** GET /files/{fileId}/download-url 返回 */
export interface DownloadUrlInfo {
  url: string
  expiresAt: string | null
}

/** POST /files/multipart/init 请求体 */
export interface MultipartInitRequest {
  fileName: string
  size: number
  mimeType: string
  sha256: string
  bizType: FileBizType
}

/** POST /files/multipart/init 返回；秒传时直接带 fileInfo */
export interface MultipartInitResponse {
  uploadId: string | null
  partSize: number
  partCount: number
  fileInfo: FileInfo | null
}

/** GET /files/multipart/{uploadId} 返回：已上传的分片编号（断点续传） */
export interface MultipartSessionInfo {
  uploadId: string
  uploadedParts: number[]
}
