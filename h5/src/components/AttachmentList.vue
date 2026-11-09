<script setup lang="ts">
import { computed, ref } from 'vue'
import { showToast } from 'vant'
import FilePreviewPopup from '@/components/FilePreviewPopup.vue'
import { downloadFile } from '@/api/file'
import { FILE_CATEGORY_COLORS, formatFileSize } from '@/utils/file'
import { formatDateTime } from '@/utils/format'
import type { UserRef } from '@/types/api'
import type { FileCategory, TranscodeStatus } from '@/types/enums'

interface AttachmentFile {
  id: string
  fileName: string
  size: number
  category: FileCategory
  transcodeStatus: TranscodeStatus | null
  uploadedAt?: string | null
  uploadedBy?: UserRef | null
}

const props = withDefaults(
  defineProps<{
    files: AttachmentFile[]
    /** 只展示文件名等紧凑模式 */
    compact?: boolean
    emptyText?: string
  }>(),
  { compact: false, emptyText: '暂无附件' },
)

const emit = defineEmits<{
  remove: [file: AttachmentFile]
}>()

const previewVisible = ref(false)
const previewFile = ref<AttachmentFile | null>(null)

const files = computed(() => props.files.filter(Boolean))

function openPreview(file: AttachmentFile): void {
  previewFile.value = file
  previewVisible.value = true
}

function selectFile(file: AttachmentFile): void {
  previewFile.value = file
}

async function download(file: AttachmentFile): Promise<void> {
  try {
    await downloadFile(file.id, file.fileName, file.size)
  } catch {
    showToast('获取下载地址失败，请重试')
  }
}
</script>

<template>
  <div class="attachment-list">
    <div v-if="files.length === 0" class="attachment-empty">{{ emptyText }}</div>
    <div v-for="file in files" :key="file.id" class="attachment-item">
      <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[file.category] }" />
      <div class="file-main" @click="openPreview(file)">
        <div class="file-name ellipsis">{{ file.fileName }}</div>
        <div class="file-meta">
          <span>{{ formatFileSize(file.size) }}</span>
          <span v-if="!compact && file.uploadedAt">· {{ formatDateTime(file.uploadedAt) }}</span>
        </div>
      </div>
      <div class="file-actions">
        <button type="button" class="icon-btn" aria-label="下载" @click="download(file)">
          <van-icon name="down" size="17" />
        </button>
        <button
          v-if="$slots.remove"
          type="button"
          class="icon-btn icon-btn--danger"
          aria-label="移除"
          @click="emit('remove', file)"
        >
          <van-icon name="cross" size="16" />
        </button>
      </div>
    </div>
    <FilePreviewPopup
      v-model="previewVisible"
      :file="previewFile"
      :files="files"
      @select="selectFile"
    />
  </div>
</template>

<style scoped>
.attachment-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.attachment-empty {
  padding: 10px 0;
  color: var(--text-placeholder);
  font-size: 12px;
  text-align: center;
}
.attachment-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 48px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.file-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex-shrink: 0;
}
.file-main {
  flex: 1;
  min-width: 0;
  padding: 4px 0;
}
.file-name {
  font-size: 13px;
  line-height: 19px;
}
.file-meta {
  display: flex;
  gap: 4px;
  margin-top: 2px;
  color: var(--text-secondary);
  font-size: 11px;
}
.file-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}
.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--brand);
}
.icon-btn--danger {
  color: var(--danger);
}
</style>
