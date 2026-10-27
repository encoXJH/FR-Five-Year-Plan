<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getFileDownloadUrl, getFilePreview } from '@/api/file'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { formatDate, formatDateTime } from '@/utils/format'
import { FILE_CATEGORY_COLORS, formatFileSize } from '@/utils/file'
import type { FileInfo, PreviewInfo } from '@/types/file'

/** 预览内容面板（UI 规范 7.7）：抽屉与审核中心内嵌面板共用 */
type PreviewFile = Pick<FileInfo, 'id' | 'fileName' | 'size' | 'category' | 'transcodeStatus'> &
  Partial<Pick<FileInfo, 'uploadedBy' | 'uploadedAt'>>

const props = defineProps<{
  file: PreviewFile | null
  /** 同批材料列表：提供时头部显示「上一份 / 下一份」切换 */
  files?: PreviewFile[]
}>()

const emit = defineEmits<{
  close: []
  select: [file: PreviewFile]
}>()

const userStore = useUserStore()

const loading = ref(false)
const errorMessage = ref('')
const errorRequestId = ref('')
const preview = ref<PreviewInfo | null>(null)

const watermarkText = computed(() => {
  const name = userStore.userInfo?.name ?? ''
  return `${name} ${formatDate(new Date())}`
})

/** 浅色平铺水印（UI 规范 7.7 假设 A11：查看人姓名 + 日期，45° 平铺，透明度 8%） */
const watermarkStyle = computed(() => {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="260" height="180"><text x="20" y="100" font-size="14" fill="#303133" transform="rotate(45 130 90)">${watermarkText.value}</text></svg>`
  return {
    backgroundImage: `url("data:image/svg+xml,${encodeURIComponent(svg)}")`,
    opacity: 0.08,
  }
})

const categoryColor = computed(() =>
  props.file ? FILE_CATEGORY_COLORS[props.file.category] : '#909399',
)

const isVideoProcessing = computed(
  () => props.file?.category === 'VIDEO' && props.file.transcodeStatus === 'PROCESSING',
)

// ---------- 上一份 / 下一份 ----------
const fileIndex = computed(() =>
  props.file && props.files ? props.files.findIndex((item) => item.id === props.file?.id) : -1,
)
const hasPrev = computed(() => fileIndex.value > 0)
const hasNext = computed(
  () => props.files !== undefined && fileIndex.value >= 0 && fileIndex.value < props.files.length - 1,
)

function goPrev(): void {
  if (hasPrev.value && props.files) emit('select', props.files[fileIndex.value - 1])
}

function goNext(): void {
  if (hasNext.value && props.files) emit('select', props.files[fileIndex.value + 1])
}

// ---------- 图片工具（UI 规范 7.7：缩放 50%–400%、旋转、1:1，CSS transform 实现） ----------
const ZOOM_MIN = 0.5
const ZOOM_MAX = 4
const ZOOM_STEP = 0.25

const zoom = ref(1)
const rotation = ref(0)

const imageStyle = computed(() => ({
  transform: `scale(${zoom.value}) rotate(${rotation.value}deg)`,
}))

function zoomIn(): void {
  zoom.value = Math.min(ZOOM_MAX, Math.round((zoom.value + ZOOM_STEP) * 100) / 100)
}

function zoomOut(): void {
  zoom.value = Math.max(ZOOM_MIN, Math.round((zoom.value - ZOOM_STEP) * 100) / 100)
}

function rotateRight(): void {
  rotation.value = (rotation.value + 90) % 360
}

function resetImage(): void {
  zoom.value = 1
  rotation.value = 0
}

// ---------- 预览加载 ----------
/** 预览地址 10 分钟过期：遇 80004 / 80005 自动重新获取一次（不提示用户），仍失败才显示错误态 */
async function fetchPreview(fileId: string): Promise<PreviewInfo> {
  try {
    return await getFilePreview(fileId, { silent: true })
  } catch (error) {
    if (error instanceof ApiError && (error.code === 80004 || error.code === 80005)) {
      return await getFilePreview(fileId, { silent: true })
    }
    throw error
  }
}

async function loadPreview(): Promise<void> {
  if (!props.file) return
  loading.value = true
  errorMessage.value = ''
  errorRequestId.value = ''
  preview.value = null
  resetImage()
  try {
    preview.value = await fetchPreview(props.file.id)
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError
        ? `${error.message || '预览加载失败'}（${error.code}）`
        : '预览加载失败，请重试或下载查看'
    errorRequestId.value = error instanceof ApiError ? error.requestId : ''
  } finally {
    loading.value = false
  }
}

async function download(): Promise<void> {
  if (!props.file) return
  try {
    const { url } = await getFileDownloadUrl(props.file.id)
    window.open(url, '_blank')
  } catch {
    ElMessage.error('获取下载地址失败，请重试')
  }
}

async function openInNewWindow(): Promise<void> {
  if (preview.value?.url) {
    window.open(preview.value.url, '_blank')
    return
  }
  await download()
}

watch(
  () => props.file?.id,
  (id) => {
    if (id) {
      loadPreview()
    } else {
      preview.value = null
      errorMessage.value = ''
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="preview-pane">
    <div class="preview-header">
      <div class="preview-title">
        <span class="file-dot" :style="{ backgroundColor: categoryColor }" />
        <span class="file-name" :title="file?.fileName">{{ file?.fileName ?? '材料预览' }}</span>
        <el-tag size="small" effect="plain">{{ file?.category ?? '' }}</el-tag>
        <span class="file-size">{{ file ? formatFileSize(file.size) : '' }}</span>
      </div>
      <div class="preview-actions">
        <template v-if="files">
          <el-button size="small" :disabled="!hasPrev" @click="goPrev">上一份</el-button>
          <el-button size="small" :disabled="!hasNext" @click="goNext">下一份</el-button>
        </template>
        <el-button size="small" @click="download">下载</el-button>
        <el-button size="small" @click="openInNewWindow">新窗口打开</el-button>
        <el-button size="small" @click="emit('close')">关闭</el-button>
      </div>
    </div>

    <div v-loading="loading" element-loading-text="正在加载预览…" class="preview-body">
      <div class="watermark" :style="watermarkStyle" />

      <template v-if="!loading">
        <el-result v-if="errorMessage" icon="error" :title="errorMessage">
          <template #sub-title>
            <span v-if="errorRequestId" class="mono request-id">请求编号：{{ errorRequestId }}</span>
          </template>
          <template #extra>
            <el-button type="primary" @click="loadPreview">重试</el-button>
            <el-button @click="download">下载查看</el-button>
          </template>
        </el-result>

        <el-alert
          v-else-if="isVideoProcessing"
          type="warning"
          :closable="false"
          title="视频转码中，请稍后再试"
          description="也可以先下载原文件查看"
          class="preview-tip"
        >
          <el-button size="small" class="tip-download" @click="download">下载原文件</el-button>
        </el-alert>

        <template v-else-if="preview">
          <template v-if="preview.previewType === 'IMAGE' && preview.url">
            <div class="image-toolbar">
              <el-button size="small" :disabled="zoom <= ZOOM_MIN" @click="zoomOut">缩小</el-button>
              <span class="zoom-text">{{ Math.round(zoom * 100) }}%</span>
              <el-button size="small" :disabled="zoom >= ZOOM_MAX" @click="zoomIn">放大</el-button>
              <el-button size="small" @click="rotateRight">旋转</el-button>
              <el-button size="small" @click="resetImage">1:1</el-button>
            </div>
            <div class="image-stage">
              <img :src="preview.url" class="preview-image" :style="imageStyle" :alt="file?.fileName" />
            </div>
          </template>
          <iframe v-else-if="preview.previewType === 'PDF' && preview.url" :src="preview.url" class="preview-frame" title="PDF 预览" />
          <!-- OFFICE 经 kkFileView 转换后有 url 时内嵌；url 为 null（未配置预览服务）时提示下载 -->
          <iframe v-else-if="preview.previewType === 'OFFICE' && preview.url" :src="preview.url" class="preview-frame" title="Office 预览" />
          <video v-else-if="preview.previewType === 'VIDEO' && preview.url" :src="preview.url" class="preview-video" controls />
          <el-empty v-else :image-size="120" description="该格式不支持在线预览，请下载查看">
            <el-button type="primary" @click="download">下载查看</el-button>
          </el-empty>
        </template>
      </template>
    </div>

    <div class="preview-footer">
      <span class="mono">{{ file?.uploadedBy?.name ?? '' }}</span>
      <span v-if="file?.uploadedAt" class="upload-time">上传于 {{ formatDateTime(file.uploadedAt) }}</span>
    </div>
  </div>
</template>

<style scoped>
.preview-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--el-bg-color);
}

.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--el-border-color-light);
  flex-shrink: 0;
}

.preview-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.file-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex-shrink: 0;
}

.file-name {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 360px;
}

.file-size {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.preview-actions {
  display: flex;
  flex-shrink: 0;
}

.preview-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  overflow: hidden;
}

.watermark {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 5;
}

.request-id {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.image-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) 0;
  flex-shrink: 0;
  z-index: 6;
}

.zoom-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
  min-width: 44px;
  text-align: center;
}

.image-stage {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: auto;
}

.preview-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  transition: transform 0.2s ease;
  transform-origin: center center;
}

.preview-frame {
  width: 100%;
  height: 100%;
  border: none;
}

.preview-video {
  max-width: 100%;
  max-height: 100%;
}

.preview-tip {
  max-width: 420px;
}

.tip-download {
  margin-top: var(--space-2);
}

.preview-footer {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-5);
  border-top: 1px solid var(--el-border-color-light);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  height: 44px;
  flex-shrink: 0;
}

.mono {
  font-family: var(--app-font-mono);
}
</style>
