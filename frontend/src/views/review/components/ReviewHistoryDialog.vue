<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import StatusTag from '@/components/StatusTag.vue'
import FilePreviewPane from '@/components/FilePreviewPane.vue'
import SubmissionTimeline from '@/views/submission/components/SubmissionTimeline.vue'
import { listReviewHistory } from '@/api/review'
import { getSubmission } from '@/api/submission'
import { formatDateTime } from '@/utils/format'
import { FILE_CATEGORY_COLORS, formatFileSize } from '@/utils/file'
import { formatMetricValue, parseSubmissionRemark } from '@/utils/submission'
import type { PageResult } from '@/types/api'
import type { FileInfo } from '@/types/file'
import type {
  ReviewHistoryQuery,
  SubmissionDetail,
  SubmissionListItem,
} from '@/types/submission'

const props = defineProps<{
  modelValue: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const loading = ref(false)
const page = ref<PageResult<SubmissionListItem>>({ items: [], total: 0, page: 1, pageSize: 20 })
const status = ref<ReviewHistoryQuery['status'] | ''>('')
const keyword = ref('')
const selectedId = ref<string | null>(null)
const detail = ref<SubmissionDetail | null>(null)
const detailLoading = ref(false)
const previewFileId = ref<string | null>(null)
let listGeneration = 0
let detailGeneration = 0

const files = computed(() => detail.value?.files ?? [])
const previewFile = computed<FileInfo | null>(() =>
  files.value.find((file) => file.id === previewFileId.value) ?? null,
)
const remarkParts = computed(() => parseSubmissionRemark(detail.value?.remark))
const submissionValueText = computed(() => detail.value
  ? formatMetricValue(detail.value.value, detail.value.statCaliber)
  : '—')
const approvedValueText = computed(() => detail.value?.approvedValue
  ? formatMetricValue(detail.value.approvedValue, detail.value.statCaliber)
  : '—')

async function openDetail(id: string): Promise<void> {
  const generation = ++detailGeneration
  selectedId.value = id
  detailLoading.value = true
  previewFileId.value = null
  try {
    const result = await getSubmission(id)
    if (generation !== detailGeneration) return
    detail.value = result
    previewFileId.value = result.files[0]?.id ?? null
  } catch {
    if (generation === detailGeneration) detail.value = null
  } finally {
    if (generation === detailGeneration) detailLoading.value = false
  }
}

async function loadHistory(): Promise<void> {
  const generation = ++listGeneration
  loading.value = true
  try {
    const result = await listReviewHistory({
      status: status.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value.page,
      pageSize: page.value.pageSize,
    })
    if (generation !== listGeneration) return
    page.value = result
    const target = result.items.find((item) => item.id === selectedId.value) ?? result.items[0]
    if (target) {
      await openDetail(target.id)
    } else {
      selectedId.value = null
      detail.value = null
      previewFileId.value = null
    }
  } finally {
    if (generation === listGeneration) loading.value = false
  }
}

function applyFilters(): void {
  page.value.page = 1
  selectedId.value = null
  void loadHistory()
}

function changePage(next: number): void {
  page.value.page = next
  void loadHistory()
}

function openPreview(file: { id: string }): void {
  previewFileId.value = file.id
}

watch(() => props.modelValue, (open) => {
  if (!open) return
  status.value = ''
  keyword.value = ''
  page.value.page = 1
  selectedId.value = null
  detail.value = null
  previewFileId.value = null
  void loadHistory()
})
</script>

<template>
  <el-dialog
    v-model="visible"
    title="历史审核"
    width="min(1320px, calc(100vw - 32px))"
    top="4vh"
    destroy-on-close
    class="review-history-dialog"
  >
    <div class="history-filters">
      <el-radio-group v-model="status" @change="applyFilters">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="APPROVED">已通过</el-radio-button>
        <el-radio-button value="REJECTED">已驳回</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="keyword"
        clearable
        placeholder="任务编号 / 指标编号或名称"
        class="history-keyword"
        @clear="applyFilters"
        @keyup.enter="applyFilters"
      />
      <el-button type="primary" @click="applyFilters">查询</el-button>
    </div>

    <div class="history-layout">
      <el-card shadow="never" class="history-list-card" v-loading="loading">
        <ul v-if="page.items.length > 0" class="history-list">
          <li
            v-for="item in page.items"
            :key="item.id"
            class="history-item"
            :class="{ 'is-active': item.id === selectedId }"
            @click="openDetail(item.id)"
          >
            <div class="history-item-head">
              <span class="mono history-code">{{ item.task?.code ?? '—' }}</span>
              <StatusTag kind="SubmissionStatus" :value="item.status" :review-node="item.currentReviewNode" />
            </div>
            <div class="history-indicator" :title="item.indicator?.name">{{ item.indicator?.name ?? '—' }}</div>
            <div class="history-meta">{{ item.unit?.name ?? '—' }} · {{ item.year }} 年</div>
            <div class="history-meta">
              审核于 {{ formatDateTime(item.reviewedAt) }} · 材料 {{ item.fileCount ?? 0 }} 份
            </div>
          </li>
        </ul>
        <el-empty v-else-if="!loading" description="暂无历史审核记录" :image-size="90" />
        <el-pagination
          v-if="page.total > page.pageSize"
          small
          :current-page="page.page"
          :page-size="page.pageSize"
          :total="page.total"
          layout="total, prev, pager, next"
          @current-change="changePage"
        />
      </el-card>

      <el-card shadow="never" class="history-detail-card" v-loading="detailLoading">
        <template v-if="detail">
          <div class="history-detail-head">
            <div>
              <div class="history-detail-title">
                <span class="mono">{{ detail.task?.code ?? '—' }}</span>
                {{ (detail.topIndicator ?? detail.indicator)?.name ?? '—' }}
              </div>
              <div class="history-meta">
                {{ detail.unit?.name ?? '—' }} · {{ detail.year }} 年 · 审核人 {{ detail.reviewer?.name ?? '—' }}
                · {{ formatDateTime(detail.reviewedAt) }}
              </div>
            </div>
            <StatusTag kind="SubmissionStatus" :value="detail.status" :review-node="detail.currentReviewNode" />
          </div>

          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="填报值">{{ submissionValueText }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.approvedValue" label="认定值">{{ approvedValueText }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.sourceNote" label="数据来源说明">{{ detail.sourceNote }}</el-descriptions-item>
            <el-descriptions-item label="完成情况说明">{{ remarkParts.remark || '—' }}</el-descriptions-item>
          </el-descriptions>

          <h3 class="section-title">历史审核材料（{{ files.length }} 份）</h3>
          <ul v-if="files.length > 0" class="file-list">
            <li v-for="file in files" :key="file.id" class="file-row">
              <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[file.category] }" />
              <span class="file-name" :title="file.fileName">{{ file.fileName }}</span>
              <span class="file-size">{{ formatFileSize(file.size) }}</span>
              <el-button link type="primary" @click="previewFileId = file.id">预览</el-button>
            </li>
          </ul>
          <div v-else class="empty-text">该记录未上传材料</div>

          <template v-if="detail.reviews.length > 0">
            <h3 class="section-title">审核意见</h3>
            <SubmissionTimeline :submissions="[detail]" :caliber="detail.statCaliber" />
          </template>

          <div v-if="previewFile" class="history-preview">
            <FilePreviewPane
              :file="previewFile"
              :files="files"
              @select="openPreview"
              @close="previewFileId = null"
            />
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="选择一条记录查看历史审核材料" :image-size="100" />
      </el-card>
    </div>
  </el-dialog>
</template>

<style scoped>
.history-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.history-keyword {
  width: 250px;
}

.history-layout {
  display: grid;
  grid-template-columns: 390px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.history-list-card,
.history-detail-card {
  max-height: 76vh;
  overflow-y: auto;
}

.history-list {
  display: flex;
  margin: 0 0 12px;
  padding: 0;
  flex-direction: column;
  gap: 8px;
  list-style: none;
}

.history-item {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-left: 3px solid transparent;
  border-radius: 6px;
  cursor: pointer;
}

.history-item:hover,
.history-item.is-active {
  border-left-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.history-item-head,
.history-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.history-code,
.history-detail-title {
  font-weight: 600;
}

.history-detail-title {
  margin-bottom: 6px;
  font-size: 16px;
  line-height: 24px;
}

.history-indicator,
.history-meta {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
}

.history-indicator {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: var(--app-font-mono);
}

.section-title {
  margin: 18px 0 10px;
  font-size: 14px;
}

.file-list {
  display: flex;
  margin: 0;
  padding: 0;
  flex-direction: column;
  gap: 6px;
  list-style: none;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.file-dot {
  width: 8px;
  height: 8px;
  flex: none;
  border-radius: 50%;
}

.file-name {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.empty-text {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.history-preview {
  height: 560px;
  margin-top: 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}

@media (max-width: 900px) {
  .history-layout {
    grid-template-columns: 1fr;
  }

  .history-list-card {
    max-height: 340px;
  }

  .history-detail-card {
    max-height: none;
  }

  .history-keyword {
    width: min(100%, 250px);
  }
}
</style>
