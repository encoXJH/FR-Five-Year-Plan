<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { showToast } from 'vant'
import OfficeDocumentPreview from './OfficeDocumentPreview.vue'
import { downloadFile, getFilePreview } from '@/api/file'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { formatDate } from '@/utils/format'
import { FILE_CATEGORY_COLORS, formatFileSize } from '@/utils/file'
import type { FileInfo, PreviewInfo } from '@/types/file'

type PreviewFile = Pick<FileInfo, 'id' | 'fileName' | 'size' | 'category' | 'transcodeStatus'> & {
  uploadedBy?: { id: string; name: string } | null
  uploadedAt?: string | null
}

const props = defineProps<{
  modelValue: boolean
  file: PreviewFile | null
  files?: PreviewFile[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  close: []
  select: [file: PreviewFile]
}>()

const userStore = useUserStore()
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const loading = ref(false)
const errorMessage = ref('')
const errorRequestId = ref('')
const preview = ref<PreviewInfo | null>(null)

const watermarkStyle = computed(() => {
  const text = `${userStore.userInfo?.name ?? ''} ${formatDate(new Date())}`
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="260" height="180"><text x="20" y="100" font-size="14" fill="#303133" transform="rotate(45 130 90)">${text}</text></svg>`
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
  zoom.value = 1
  rotation.value = 0
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
    await downloadFile(props.file.id, props.file.fileName, props.file.size)
  } catch {
    showToast('获取下载地址失败，请重试')
  }
}

watch(
  () => [props.modelValue, props.file?.id],
  ([open]) => {
    if (open && props.file) void loadPreview()
  },
  { immediate: true },
)
</script>

<template>
  <van-popup
    v-model:show="visible"
    position="bottom"
    :style="{ height: '100%', width: '100%', maxWidth: '720px', left: '50%', transform: 'translateX(-50%)' }"
    :round="false"
    teleport="body"
  >
    <div class="preview-popup">
      <div class="preview-header">
        <button type="button" class="close-btn" aria-label="关闭" @click="visible = false">
          <van-icon name="cross" size="20" />
        </button>
        <div class="preview-title">
          <span class="file-dot" :style="{ backgroundColor: categoryColor }" />
          <span class="file-name ellipsis">{{ file?.fileName ?? '材料预览' }}</span>
          <span class="file-size">{{ file ? formatFileSize(file.size) : '' }}</span>
        </div>
        <button type="button" class="download-btn" @click="download">
          <van-icon name="down" size="18" />
        </button>
      </div>

      <div v-if="files && files.length > 1" class="preview-nav">
        <van-button size="small" :disabled="!hasPrev" @click="goPrev">上一份</van-button>
        <span class="muted small">{{ fileIndex + 1 }} / {{ files.length }}</span>
        <van-button size="small" :disabled="!hasNext" @click="goNext">下一份</van-button>
      </div>

      <div class="preview-body">
        <div class="watermark" :style="watermarkStyle" />
        <div v-if="loading" class="preview-tip">正在加载预览…</div>
        <div v-else-if="errorMessage" class="preview-error">
          <van-icon name="warning-o" size="34" />
          <p>{{ errorMessage }}</p>
          <p v-if="errorRequestId" class="small muted">请求编号：{{ errorRequestId }}</p>
          <div class="row">
            <van-button size="small" type="primary" @click="loadPreview">重试</van-button>
            <van-button size="small" @click="download">下载查看</van-button>
          </div>
        </div>
        <div v-else-if="isVideoProcessing" class="preview-error">
          <van-icon name="video-o" size="34" />
          <p>视频转码中，请稍后再试</p>
          <van-button size="small" @click="download">下载原文件</van-button>
        </div>
        <template v-else-if="preview">
          <template v-if="preview.previewType === 'IMAGE' && preview.url">
            <div class="image-toolbar">
              <van-button size="mini" :disabled="zoom <= ZOOM_MIN" @click="zoomOut">缩小</van-button>
              <span class="zoom-text">{{ Math.round(zoom * 100) }}%</span>
              <van-button size="mini" :disabled="zoom >= ZOOM_MAX" @click="zoomIn">放大</van-button>
              <van-button size="mini" @click="rotateRight">旋转</van-button>
              <van-button size="mini" @click="zoom = 1; rotation = 0">1:1</van-button>
            </div>
            <div class="image-stage">
              <img :src="preview.url" class="preview-image" :style="imageStyle" :alt="file?.fileName" />
            </div>
          </template>
          <iframe
            v-else-if="preview.previewType === 'PDF' && preview.url"
            :src="preview.url"
            class="preview-frame"
            title="PDF 预览"
          />
          <OfficeDocumentPreview
            v-else-if="preview.previewType === 'OFFICE' && preview.url && preview.renderMode === 'CLIENT'"
            :src="preview.url"
            :file-name="file?.fileName ?? ''"
          />
          <iframe
            v-else-if="preview.previewType === 'OFFICE' && preview.url"
            :src="preview.url"
            class="preview-frame"
            title="Office 预览"
          />
          <video
            v-else-if="preview.previewType === 'VIDEO' && preview.url"
            :src="preview.url"
            class="preview-video"
            controls
            playsinline
            webkit-playsinline
          />
          <div v-else class="preview-error">
            <van-icon name="description" size="34" />
            <p>该格式不支持在线预览，请下载查看</p>
            <van-button size="small" type="primary" @click="download">下载查看</van-button>
          </div>
        </template>
      </div>

      <div class="preview-footer">
        <span>{{ file?.uploadedBy?.name ?? '' }}</span>
        <span v-if="file?.uploadedAt">上传于 {{ formatDate(file.uploadedAt) }}</span>
      </div>
    </div>
  </van-popup>
</template>

<style scoped>
.preview-popup {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
}
.preview-header {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  padding: 0 10px;
  padding-top: var(--safe-top);
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}
.close-btn,
.download-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text);
}
.preview-title {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.file-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex-shrink: 0;
}
.file-name {
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
}
.file-size {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 11px;
}
.preview-nav {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--line);
}
.preview-body {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #f6f8fb;
}
.watermark {
  position: absolute;
  inset: 0;
  z-index: 5;
  pointer-events: none;
}
.preview-tip,
.preview-error {
  position: relative;
  z-index: 6;
  padding: 24px;
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
}
.preview-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  max-width: 320px;
}
.preview-error p {
  margin: 0;
}
.image-toolbar {
  position: relative;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  flex-shrink: 0;
}
.zoom-text {
  min-width: 42px;
  color: var(--text-secondary);
  font-size: 12px;
  text-align: center;
}
.image-stage {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 0;
  overflow: auto;
  padding: 8px;
  position: relative;
  z-index: 6;
}
.preview-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  transition: transform 0.2s ease;
}
.preview-frame {
  position: relative;
  z-index: 6;
  width: 100%;
  height: 100%;
  border: none;
}
.preview-video {
  position: relative;
  z-index: 6;
  max-width: 100%;
  max-height: 100%;
}
.preview-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 14px calc(var(--safe-bottom) / 2);
  border-top: 1px solid var(--line);
  color: var(--text-secondary);
  font-size: 12px;
  flex-shrink: 0;
}
</style>
