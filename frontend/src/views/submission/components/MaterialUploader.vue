<script setup lang="ts">
import { ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import {
  completeMultipart,
  getFileDownloadUrl,
  getMultipartSession,
  initMultipart,
  uploadFile,
  uploadPart,
} from '@/api/file'
import FilePreviewDrawer from '@/components/FilePreviewDrawer.vue'
import { ApiError } from '@/utils/request'
import { sha256File } from '@/utils/sha256'
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
  /** 0–100 */
  progress: number
  uploadedParts: number
  partCount: number
  /** 失败原因（含错误码，UI 规范 7.6） */
  error?: string
  /** 校验失败（格式 / 大小）不允许重试 */
  retryable: boolean
  /** 秒传 */
  instant: boolean
  multipart: boolean
  fileInfo?: FileInfo
  file?: File
  uploadId?: string
  partSize?: number
  uploadedPartSet?: Set<number>
  controller?: AbortController
}

const props = withDefaults(
  defineProps<{
    /** 已完成材料的 fileId 列表（v-model） */
    modelValue: string[]
    /** 已有草稿绑定的材料（只读展示 + 可删除） */
    initialFiles?: FileInfo[]
    /** 只读（审核中 / 已提交）：不显示上传区和删除按钮 */
    disabled?: boolean
    /** 文件用途（API 第 9 章 FileBizType），默认填报材料 */
    bizType?: FileBizType
  }>(),
  { bizType: 'SUBMISSION_ATTACHMENT' },
)

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

const items = ref<UploadItem[]>([])
const dragging = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const previewVisible = ref(false)
const previewFile = ref<FileInfo | null>(null)

/** 已有草稿的材料只初始化一次，避免覆盖上传中的本地状态 */
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

function doneIds(): string[] {
  return items.value.filter((item) => item.status === 'done' && item.fileInfo).map((item) => item.fileInfo!.id)
}

function syncModel(): void {
  emit('update:modelValue', doneIds())
}

function pushErrorItem(fileName: string, size: number, message: string): void {
  items.value.push({
    key: crypto.randomUUID(),
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

/** 前端预校验（UI 规范 7.6：不合格直接显示红色错误行，不发起上传） */
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
      key: crypto.randomUUID(),
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

function onDrop(event: DragEvent): void {
  dragging.value = false
  if (props.disabled) return
  addFiles(Array.from(event.dataTransfer?.files ?? []))
}

function isCancel(error: unknown): boolean {
  return axios.isCancel(error) || (error instanceof Error && error.name === 'CanceledError')
}

function isPaused(item: UploadItem): boolean {
  return item.status === 'paused'
}

function markError(item: UploadItem, error: unknown): void {
  item.status = 'error'
  item.error =
    error instanceof ApiError ? `${error.message}（${error.code}）` : '上传失败，请重试'
}

function markDone(item: UploadItem, info: FileInfo): void {
  item.status = 'done'
  item.progress = 100
  item.fileInfo = info
  syncModel()
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
  // 分片上传：先算 sha256（校验中），init 命中秒传则直接完成
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
    if (!init.uploadId) {
      throw new Error('init response missing uploadId')
    }
    item.uploadId = init.uploadId
    item.partSize = init.partSize
    item.partCount = init.partCount
    item.uploadedPartSet = new Set()
    item.uploadedParts = 0
    await uploadParts(item)
  } catch (error) {
    if (!isCancel(error) && !isPaused(item)) markError(item, error)
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
      await uploadPart(item.uploadId!, partNumber, blob, {
        signal: item.controller.signal,
        onUploadProgress: (event) => {
          const partRatio = event.total ? event.loaded / event.total : 0
          item.progress = Math.min(
            99,
            Math.round(((item.uploadedParts + partRatio) / total) * 100),
          )
        },
      })
    } catch (error) {
      if (!isCancel(error) && !isPaused(item)) markError(item, error)
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

/** 断点续传：先查询已上传分片，从断点继续（API 8.2-3） */
async function resume(item: UploadItem): Promise<void> {
  if (!item.uploadId) {
    await startUpload(item)
    return
  }
  try {
    const session = await getMultipartSession(item.uploadId)
    item.uploadedPartSet = new Set(session.uploadedParts ?? [])
    item.uploadedParts = item.uploadedPartSet.size
  } catch {
    ElMessage.error('上传会话已过期，请删除后重新上传')
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
  void startUpload(item)
}

function removeItem(item: UploadItem): void {
  if (item.status === 'uploading' || item.status === 'hashing') {
    item.controller?.abort()
  }
  items.value = items.value.filter((entry) => entry.key !== item.key)
  syncModel()
}

function openPreview(item: UploadItem): void {
  if (!item.fileInfo) return
  previewFile.value = item.fileInfo
  previewVisible.value = true
}

async function download(item: UploadItem): Promise<void> {
  if (!item.fileInfo) return
  try {
    const { url } = await getFileDownloadUrl(item.fileInfo.id)
    window.open(url, '_blank')
  } catch {
    ElMessage.error('获取下载地址失败，请重试')
  }
}
</script>

<template>
  <div class="material-uploader">
    <div
      v-if="!disabled"
      class="drop-zone"
      :class="{ 'is-dragging': dragging }"
      @click="fileInput?.click()"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <div class="drop-main">将文件拖到此处，或 <span class="drop-link">点击上传</span></div>
      <div class="drop-sub">支持 doc/docx、xls/xlsx、ppt/pptx、pdf、jpg/png、mp4/mov 等格式</div>
      <div class="drop-sub">文档/PDF ≤ 50MB，图片 ≤ 20MB，视频 ≤ 500MB；超过 20MB 自动分片上传</div>
      <input ref="fileInput" type="file" multiple hidden @change="onPick" />
    </div>

    <ul v-if="items.length > 0" class="file-list">
      <li v-for="item in items" :key="item.key" class="file-row" :class="{ 'is-error': item.status === 'error' }">
        <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[item.category] }" />
        <div class="file-main">
          <div class="file-line">
            <span class="file-name" :title="item.fileName">{{ item.fileName }}</span>
            <span class="file-size">{{ formatFileSize(item.size) }}</span>
            <el-tag v-if="item.instant" size="small" type="success" effect="plain">秒传</el-tag>
          </div>
          <div v-if="item.status === 'uploading' || item.status === 'hashing' || item.status === 'paused'" class="file-progress">
            <el-progress :percentage="item.progress" :stroke-width="6" :status="item.status === 'paused' ? 'warning' : undefined" />
            <span class="progress-note">
              <template v-if="item.status === 'hashing'">校验中（计算 sha256）…</template>
              <template v-else-if="item.multipart">分片 {{ item.uploadedParts }}/{{ item.partCount }}</template>
            </span>
          </div>
          <div v-else-if="item.status === 'error'" class="file-error">{{ item.error }}</div>
        </div>
        <div class="file-actions">
          <template v-if="!disabled">
            <el-button v-if="item.status === 'uploading' && item.multipart" link type="primary" @click="pause(item)">暂停</el-button>
            <el-button v-if="item.status === 'paused'" link type="primary" @click="resume(item)">继续</el-button>
            <el-button v-if="item.status === 'error' && item.retryable" link type="primary" @click="retry(item)">重试</el-button>
          </template>
          <el-button v-if="item.status === 'done'" link type="primary" @click="openPreview(item)">预览</el-button>
          <el-button v-if="item.status === 'done'" link type="primary" @click="download(item)">下载</el-button>
          <el-button v-if="!disabled && item.status !== 'hashing'" link type="danger" @click="removeItem(item)">
            {{ item.status === 'done' ? '删除' : '取消' }}
          </el-button>
        </div>
      </li>
    </ul>

    <FilePreviewDrawer v-model="previewVisible" :file="previewFile" />
  </div>
</template>

<style scoped>
.drop-zone {
  border: 1px dashed var(--el-border-color);
  border-radius: var(--radius-card);
  height: 140px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  cursor: pointer;
  transition: border-color 0.2s;
  background: var(--el-bg-color);
}

.drop-zone:hover,
.drop-zone.is-dragging {
  border-color: var(--el-color-primary);
}

.drop-main {
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.drop-link {
  color: var(--el-color-primary);
}

.drop-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.file-list {
  list-style: none;
  margin: var(--space-3) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.file-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-bg-color);
}

.file-row.is-error {
  border-color: var(--el-color-danger-light-8);
  background: var(--el-color-danger-light-9);
}

.file-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex-shrink: 0;
}

.file-main {
  flex: 1;
  min-width: 0;
}

.file-line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.file-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.file-progress {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-1);
}

.file-progress :deep(.el-progress) {
  flex: 1;
}

.progress-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.file-error {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--el-color-danger);
}

.file-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
</style>
