import type { AxiosRequestConfig } from 'axios'
import { http } from '@/utils/request'
import type { FileBizType } from '@/types/enums'
import type {
  DownloadUrlInfo,
  FileInfo,
  MultipartInitRequest,
  MultipartInitResponse,
  MultipartSessionInfo,
  PreviewInfo,
} from '@/types/file'

/** 直接上传（≤ 20MB，multipart/form-data，API 8.1） */
export function uploadFile(
  file: File,
  bizType: FileBizType,
  config?: AxiosRequestConfig,
): Promise<FileInfo> {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', bizType)
  return http.post('/files', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
    ...config,
  })
}

export function initMultipart(data: MultipartInitRequest): Promise<MultipartInitResponse> {
  return http.post('/files/multipart/init', data)
}

/** 上传分片（application/octet-stream，分片编号从 1 开始） */
export function uploadPart(
  uploadId: string,
  partNumber: number,
  part: Blob,
  config?: AxiosRequestConfig,
): Promise<unknown> {
  return http.put(`/files/multipart/${uploadId}/parts/${partNumber}`, part, {
    headers: { 'Content-Type': 'application/octet-stream' },
    timeout: 300000,
    ...config,
  })
}

/** 查询已上传分片（断点续传） */
export function getMultipartSession(uploadId: string): Promise<MultipartSessionInfo> {
  return http.get(`/files/multipart/${uploadId}`)
}

export function completeMultipart(uploadId: string): Promise<FileInfo> {
  return http.post(`/files/multipart/${uploadId}/complete`)
}

export function getFilePreview(fileId: string, config?: AxiosRequestConfig): Promise<PreviewInfo> {
  return http.get(`/files/${fileId}/preview`, undefined, config)
}

export function getFileDownloadUrl(fileId: string): Promise<DownloadUrlInfo> {
  return http.get(`/files/${fileId}/download-url`)
}
