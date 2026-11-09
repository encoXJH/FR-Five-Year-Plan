<script setup lang="ts">
import { ref, watch } from 'vue'
import axios from 'axios'
import { showToast } from 'vant'
import {
  completeMultipart,
  getMultipartSession,
  getPartUploadUrl,
  initMultipart,
  uploadFile,
  uploadPart,
  uploadPartDirect,
} from '@/api/file'
import FilePreviewPopup from '@/components/FilePreviewPopup.vue'
import { ApiError } from '@/utils/request'
import { sha256File } from '@/utils/sha256'
import { createUuid } from '@/utils/uuid'
import {
  DIRECT_UPLOAD_MAX_SIZE,
  FILE_CATEGORY_COLORS,
  fileCategoryOf,
  formatFileSize,
  maxSizeOf,
} from '@/utils/file'
import type { FileBizType, FileCategory } from '@/types/enums'
import type { FileInfo } from '@/types/file'

type UploadStatus = 'hashing' | 'uploading' | 'paused' | 'done' | 'error'

interface UploadItem {
  key: string
  fileName: string
  size: number
  category: FileCategory
  status: UploadStatus
  progress: number
  uploadedParts: number
  partCount: number
  error?: string
  retryable: boolean
  instant: boolean
  multipart: boolean
  directUpload?: boolean
  fileInfo?: FileInfo
  file?: File
  uploadId?: string
  partSize?: number
  uploadedPartSet?: Set<number>
  controller?: AbortController
}

const props = withDefaults(
  defineProps<{
    modelValue: string[]
    initialFiles?: FileInfo[]
    disabled?: boolean
    bizType?: FileBizType
    hint?: string
  }>(),
  {
    bizType: 'SUBMISSION_ATTACHMENT',
    hint: '支持 doc/docx、xls/xlsx、ppt/pptx、pdf、图片、视频；超过 20MB 自动分片',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
  uploaded: [fileInfo: FileInfo]
}>()

const items = ref<UploadItem[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const previewVisible = ref(false)
const previewFile = ref<FileInfo | null>(null)

let initialized = false
watch(
  () => props.initialFiles,
  (files) => {
    if (initialized || !files) return
    initialized = true
    items.value = files.map((info) => ({
      key: `bound-${info.id}`,
      fileName: info.fileName,
      size: info.size,
      category: info.category,
      status: 'done' as const,
      progress: 100,
      uploadedParts: 0,
      partCount: 0,
      retryable: false,
      instant: false,
      multipart: false,
      fileInfo: info,
    }))
  },
  { immediate: true },
)

function syncModel(): void {
  emit(
    'update:modelValue',
    items.value.filter((item) => item.status === 'done' && item.fileInfo).map((item) => item.fileInfo!.id),
  )
}

function pushErrorItem(fileName: string, size: number, message: string): void {
  items.value.push({
    key: createUuid(),
    fileName,
    size,
    category: 'ARCHIVE',
    status: 'error',
    progress: 0,
    uploadedParts: 0,
    partCount: 0,
    error: message,
    retryable: false,
    instant: false,
    multipart: false,
  })
}

function addFiles(files: File[]): void {
  for (const file of files) {
    const category = fileCategoryOf(file.name)
    if (!category) {
      pushErrorItem(file.name, file.size, '格式不支持（80001）')
      continue
    }
    const maxSize = maxSizeOf(category)
    if (file.size > maxSize) {
      pushErrorItem(file.name, file.size, `超过 ${formatFileSize(maxSize)} 上限（80002）`)
      continue
    }
    const item: UploadItem = {
      key: createUuid(),
      fileName: file.name,
      size: file.size,
      category,
      status: 'uploading',
      progress: 0,
      uploadedParts: 0,
      partCount: 0,
      retryable: true,
      instant: false,
      multipart: file.size > DIRECT_UPLOAD_MAX_SIZE,
      file,
    }
    items.value.push(item)
    void startUpload(item)
  }
}

function onPick(event: Event): void {
  const input = event.target as HTMLInputElement
  addFiles(Array.from(input.files ?? []))
  input.value = ''
}

function isCancel(error: unknown): boolean {
  return axios.isCancel(error) || (error instanceof Error && error.name === 'CanceledError')
}

function markError(item: UploadItem, error: unknown): void {
  item.status = 'error'
  item.error = error instanceof ApiError ? `${error.message}（${error.code}）` : '上传失败，请重试'
}

function markDone(item: UploadItem, info: FileInfo): void {
  item.status = 'done'
  item.progress = 100
  item.fileInfo = info
  syncModel()
  emit('uploaded', info)
  showToast('上传成功')
}

async function startUpload(item: UploadItem): Promise<void> {
  if (!item.file) return
  if (!item.multipart) {
    item.status = 'uploading'
    item.controller = new AbortController()
    try {
      const info = await uploadFile(item.file, props.bizType, {
        signal: item.controller.signal,
        onUploadProgress: (event) => {
          if (event.total) {
            item.progress = Math.min(99, Math.round((event.loaded / event.total) * 100))
          }
        },
      })
      markDone(item, info)
    } catch (error) {
      if (!isCancel(error)) markError(item, error)
    }
    return
  }
  item.status = 'hashing'
  try {
    const sha256 = await sha256File(item.file, (ratio) => {
      item.progress = Math.round(ratio * 100)
    })
    const init = await initMultipart({
      fileName: item.fileName,
      size: item.size,
      mimeType: item.file.type || 'application/octet-stream',
      sha256,
      bizType: props.bizType,
    })
    if (init.fileInfo) {
      item.instant = true
      markDone(item, init.fileInfo)
      return
    }
    if (!init.uploadId) throw new Error('init response missing uploadId')
    item.uploadId = init.uploadId
    item.partSize = init.partSize
    item.partCount = init.partCount
    item.directUpload = init.directUpload
    item.uploadedPartSet = new Set()
    item.uploadedParts = 0
    await uploadParts(item)
  } catch (error) {
    if (!isCancel(error) && (item.status as string) !== 'paused') markError(item, error)
  }
}

async function uploadParts(item: UploadItem): Promise<void> {
  item.status = 'uploading'
  const total = item.partCount
  const partSize = item.partSize ?? DIRECT_UPLOAD_MAX_SIZE
  for (let partNumber = 1; partNumber <= total; partNumber += 1) {
    if (item.uploadedPartSet?.has(partNumber)) continue
    if (item.status !== 'uploading') return
    const start = (partNumber - 1) * partSize
    const blob = item.file!.slice(start, Math.min(start + partSize, item.size))
    item.controller = new AbortController()
    try {
      const config = {
        signal: item.controller.signal,
        onUploadProgress: (event: { loaded: number; total?: number }) => {
          const partRatio = event.total ? event.loaded / event.total : 0
          item.progress = Math.min(99, Math.round(((item.uploadedParts + partRatio) / total) * 100))
        },
      }
      if (item.directUpload) {
        const { url } = await getPartUploadUrl(item.uploadId!, partNumber)
        await uploadPartDirect(url, blob, config)
      } else {
        await uploadPart(item.uploadId!, partNumber, blob, config)
      }
    } catch (error) {
      if (!isCancel(error) && (item.status as string) !== 'paused') markError(item, error)
      return
    }
    item.uploadedPartSet?.add(partNumber)
    item.uploadedParts = item.uploadedPartSet?.size ?? 0
    item.progress = Math.round((item.uploadedParts / total) * 100)
  }
  try {
    const info = await completeMultipart(item.uploadId!)
    markDone(item, info)
  } catch (error) {
    if (!isCancel(error)) markError(item, error)
  }
}

function pause(item: UploadItem): void {
  item.status = 'paused'
  item.controller?.abort()
}

async function resume(item: UploadItem): Promise<void> {
  if (!item.uploadId) {
    await startUpload(item)
    return
  }
  try {
    const session = await getMultipartSession(item.uploadId)
    item.directUpload = session.directUpload
    item.uploadedPartSet = new Set(session.uploadedParts ?? [])
    item.uploadedParts = item.uploadedPartSet.size
  } catch {
    showToast('上传会话已过期，请删除后重新上传')
    markError(item, new Error('上传会话已过期'))
    return
  }
  await uploadParts(item)
}

function retry(item: UploadItem): void {
  item.error = undefined
  item.progress = 0
  item.uploadedParts = 0
  item.uploadedPartSet = new Set()
  item.uploadId = undefined
  item.directUpload = false
  void startUpload(item)
}

function removeItem(item: UploadItem): void {
  if (item.status === 'uploading' || item.status === 'hashing') item.controller?.abort()
  items.value = items.value.filter((entry) => entry.key !== item.key)
  syncModel()
}

function openPreview(item: UploadItem): void {
  if (!item.fileInfo) return
  previewFile.value = item.fileInfo
  previewVisible.value = true
}
</script>

<template>
  <div class="uploader">
    <button v-if="!disabled" type="button" class="upload-trigger" @click="fileInput?.click()">
      <van-icon name="plus" size="22" />
      <span>上传附件</span>
      <small>{{ hint }}</small>
      <input ref="fileInput" type="file" multiple hidden @change="onPick" />
    </button>

    <ul v-if="items.length > 0" class="upload-list">
      <li
        v-for="item in items"
        :key="item.key"
        class="upload-item"
        :class="{ 'is-error': item.status === 'error' }"
      >
        <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[item.category] }" />
        <div class="upload-main">
          <div class="upload-line">
            <span class="upload-name ellipsis">{{ item.fileName }}</span>
            <span class="upload-size">{{ formatFileSize(item.size) }}</span>
            <span v-if="item.instant" class="chip chip--success">秒传</span>
          </div>
          <div
            v-if="item.status === 'uploading' || item.status === 'hashing' || item.status === 'paused'"
            class="upload-progress"
          >
            <div class="progress progress--sm">
              <div class="progress__bar" :style="{ width: `${item.progress}%` }" />
            </div>
            <span class="small muted">
              <template v-if="item.status === 'hashing'">校验中…</template>
              <template v-else-if="item.multipart">分片 {{ item.uploadedParts }}/{{ item.partCount }}</template>
              <template v-else>{{ item.progress }}%</template>
            </span>
          </div>
          <div v-else-if="item.status === 'error'" class="upload-error">{{ item.error }}</div>
        </div>
        <div class="upload-actions">
          <button
            v-if="!disabled && item.status === 'uploading' && item.multipart"
            type="button"
            class="text-btn"
            @click="pause(item)"
          >暂停</button>
          <button
            v-if="!disabled && item.status === 'paused'"
            type="button"
            class="text-btn"
            @click="resume(item)"
          >继续</button>
          <button
            v-if="!disabled && item.status === 'error' && item.retryable"
            type="button"
            class="text-btn"
            @click="retry(item)"
          >重试</button>
          <button
            v-if="item.status === 'done'"
            type="button"
            class="text-btn"
            @click="openPreview(item)"
          >预览</button>
          <button
            v-if="!disabled && item.status !== 'hashing'"
            type="button"
            class="text-btn text-btn--danger"
            @click="removeItem(item)"
          >{{ item.status === 'done' ? '删除' : '取消' }}</button>
        </div>
      </li>
    </ul>

    <FilePreviewPopup v-model="previewVisible" :file="previewFile" />
  </div>
</template>

<style scoped>
.upload-trigger {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  min-height: 96px;
  border: 1px dashed #c6d2e4;
  border-radius: 12px;
  background: #f8fafd;
  color: var(--brand);
  font-size: 14px;
  padding: 12px;
}
.upload-trigger small {
  color: var(--text-placeholder);
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}
.upload-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.upload-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}
.upload-item.is-error {
  border-color: #f5c1c1;
  background: #fdf7f7;
}
.file-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex-shrink: 0;
}
.upload-main {
  flex: 1;
  min-width: 0;
}
.upload-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.upload-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
}
.upload-size {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 11px;
}
.upload-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.upload-progress .progress {
  flex: 1;
}
.upload-error {
  margin-top: 4px;
  color: var(--danger);
  font-size: 12px;
}
.upload-actions {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex-shrink: 0;
}
.text-btn {
  min-height: 30px;
  padding: 0 4px;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 12px;
}
.text-btn--danger {
  color: var(--danger);
}
</style>
