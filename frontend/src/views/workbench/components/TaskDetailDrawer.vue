<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import StatusTag from '@/components/StatusTag.vue'
import FilePreviewDrawer from '@/components/FilePreviewDrawer.vue'
import { getTask } from '@/api/task'
import { listTaskAdjustments } from '@/api/adjustment'
import { getSubmission, listSubmissions } from '@/api/submission'
import { useUserStore } from '@/stores/user'
import { formatDateTime, formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import { primaryTaskIndicator, progressPercentage } from '@/utils/task'
import { summarizeItems } from '@/utils/adjustment'
import { enumLabel } from '@/utils/enumLabels'
import type { TaskDetail } from '@/types/task'
import type { AdjustmentListItem } from '@/types/adjustment'
import type { FileInfo } from '@/types/file'
import type { SubmissionDetail, SubmissionListItem } from '@/types/submission'
import type { StatCaliber } from '@/types/enums'

const props = defineProps<{
  modelValue: boolean
  taskId: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const loading = ref(false)
const task = ref<TaskDetail | null>(null)
const activeTab = ref('info')

const userStore = useUserStore()

const primaryIndicator = computed(() => primaryTaskIndicator(task.value))
const caliber = computed<StatCaliber>(() => primaryIndicator.value?.statCaliber ?? 'INCREMENT')

// ---------- 调整历史 ----------
const adjustments = ref<AdjustmentListItem[]>([])
const adjustmentsLoaded = ref(false)

function adjustmentSummary(item: AdjustmentListItem): string {
  return summarizeItems(item.items ?? [], primaryIndicator.value?.unitLabel)
}

function transferLine(item: AdjustmentListItem): string {
  const from = item.fromUnit?.name ?? task.value?.unit?.name ?? ''
  const to = item.toUnit?.name ?? ''
  return to ? `${from} → ${to}` : ''
}

// ---------- 提交记录与佐证材料 ----------
const submissions = ref<SubmissionListItem[]>([])
const submissionDetail = ref<SubmissionDetail | null>(null)
const submissionLoading = ref(false)
const previewVisible = ref(false)
const previewFile = ref<FileInfo | null>(null)

async function openSubmission(item: SubmissionListItem): Promise<void> {
  submissionLoading.value = true
  try {
    submissionDetail.value = await getSubmission(item.id)
  } finally {
    submissionLoading.value = false
  }
}

/** 草稿未提交时 revisionNo 为 0，不能显示成「第 0 次提交」。 */
function submissionRoundLabel(revisionNo: number | null | undefined): string {
  return typeof revisionNo === 'number' && revisionNo > 0 ? `第 ${revisionNo} 次提交` : '尚未提交'
}

function openMaterial(file: FileInfo): void {
  previewFile.value = file
  previewVisible.value = true
}

async function load(): Promise<void> {
  if (!props.taskId) return
  loading.value = true
  try {
    const [detail, history, submissionItems] = await Promise.all([
      getTask(props.taskId),
      listTaskAdjustments(props.taskId).catch(() => [] as AdjustmentListItem[]),
      listSubmissions(props.taskId).catch(() => [] as SubmissionListItem[]),
    ])
    task.value = detail
    adjustments.value = history
    submissions.value = submissionItems
    adjustmentsLoaded.value = true
  } finally {
    loading.value = false
  }
}

watch(visible, (value) => {
  if (value) {
    task.value = null
    adjustments.value = []
    submissions.value = []
    submissionDetail.value = null
    previewFile.value = null
    previewVisible.value = false
    adjustmentsLoaded.value = false
    activeTab.value = 'info'
    load()
  }
})
</script>

<template>
  <el-dialog
    v-model="visible"
    width="min(720px, calc(100vw - 32px))"
    align-center
    class="task-detail-dialog"
    :title="task ? `任务详情 · ${task.code}` : '任务详情'"
  >
    <div v-loading="loading">
      <template v-if="task">
        <el-alert
          v-if="task.isHistorical"
          type="info"
          :closable="false"
          title="历史任务只读，可查看年度目标、完成值和调整记录"
          class="history-alert"
        />
        <el-tabs v-model="activeTab">
          <el-tab-pane label="任务信息" name="info">
            <el-descriptions :column="2" border size="small" class="detail-desc">
              <el-descriptions-item label="任务编号">{{ task.code }}</el-descriptions-item>
              <el-descriptions-item label="任务状态">
                <StatusTag kind="TaskStatus" :value="task.status" />
              </el-descriptions-item>
              <el-descriptions-item label="所属指标" :span="2">
                {{ primaryIndicator ? `${primaryIndicator.code} ${primaryIndicator.name}` : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="执行单元">
                {{ task.unit ? `${task.unit.code} ${task.unit.name}` : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="学院负责人">{{ task.owner?.name ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="统计口径">
                <StatusTag v-if="primaryIndicator?.statCaliber" kind="StatCaliber" :value="primaryIndicator.statCaliber" />
                <template v-else>—</template>
              </el-descriptions-item>
              <el-descriptions-item label="五年目标">
                {{ formatTargetValue(task.totalTarget ?? null, caliber) }}{{ primaryIndicator?.unitLabel ? ` ${primaryIndicator.unitLabel}` : '' }}
              </el-descriptions-item>
            </el-descriptions>

            <h3 class="section-title">年度目标与完成</h3>
            <el-table :data="task.years ?? []" border size="small">
              <el-table-column prop="year" label="年度" width="70" align="center" />
              <el-table-column label="目标" width="100" align="right">
                <template #default="{ row }">{{ formatTargetValue(row.target, caliber) }}</template>
              </el-table-column>
              <el-table-column label="已完成（审核通过）" width="120" align="right">
                <template #default="{ row }">{{ formatTargetValue(row.actualValue, caliber) }}</template>
              </el-table-column>
              <el-table-column label="完成率" min-width="140">
                <template #default="{ row }">
                  <template v-if="row.completionRate !== null && row.completionRate !== undefined">
                    <el-progress
                      :percentage="progressPercentage(row.completionRate)"
                      :stroke-width="8"
                      :color="row.completionRate >= 1 ? '#67C23A' : '#2B5BC0'"
                    >
                      <span class="rate-text">{{ formatPercent(row.completionRate) }}</span>
                    </el-progress>
                  </template>
                  <span v-else class="empty-text">—</span>
                </template>
              </el-table-column>
              <el-table-column label="年度状态" width="100" align="center">
                <template #default="{ row }">
                  <StatusTag kind="TaskYearStatus" :value="row.yearStatus" />
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="提交记录" name="submissions">
            <div v-if="submissions.length > 0" class="submission-list">
              <button
                v-for="item in submissions"
                :key="item.id"
                type="button"
                class="submission-record"
                :class="{ 'is-active': submissionDetail?.id === item.id }"
                @click="openSubmission(item)"
              >
                <span>{{ item.year }} 年 · {{ submissionRoundLabel(item.revisionNo) }}</span>
                <StatusTag kind="SubmissionStatus" :value="item.status" />
                <small>{{ formatDateTime(item.submittedAt ?? item.createdAt) }} · 材料 {{ item.fileCount ?? 0 }} 份</small>
              </button>
            </div>
            <el-empty v-else :image-size="80" description="该任务暂无提交记录" />

            <div v-if="submissionDetail" v-loading="submissionLoading" class="submission-materials">
              <h3 class="section-title">提交材料（{{ submissionDetail.files.length }} 份）</h3>
              <div v-if="submissionDetail.files.length > 0" class="material-list">
                <el-button
                  v-for="file in submissionDetail.files"
                  :key="file.id"
                  text
                  type="primary"
                  @click="openMaterial(file)"
                >
                  {{ file.fileName }}
                </el-button>
              </div>
              <el-empty v-else :image-size="60" description="本次提交未上传材料" />
            </div>
          </el-tab-pane>

          <el-tab-pane label="调整历史" name="adjustments">
            <el-timeline v-if="adjustments.length > 0" class="history-timeline">
              <el-timeline-item
                v-for="item in adjustments"
                :key="item.id"
                :timestamp="formatDateTime(item.createdAt)"
                placement="top"
                :type="item.status === 'APPROVED' ? 'success' : item.status === 'REJECTED' || item.status === 'WITHDRAWN' ? 'info' : 'primary'"
              >
                <div class="history-head">
                  <span class="history-type">
                    {{ enumLabel('AdjustmentType', item.type, userStore.enums) }}
                  </span>
                  <StatusTag kind="AdjustmentStatus" :value="item.status" />
                </div>
                <div class="history-line">{{ adjustmentSummary(item) }}</div>
                <div v-if="transferLine(item)" class="history-line">{{ transferLine(item) }}</div>
                <div class="history-meta">
                  申请人：{{ item.applicant?.name ?? '—' }}
                  <template v-if="item.reason"> · {{ item.reason }}</template>
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty
              v-else-if="adjustmentsLoaded"
              :image-size="80"
              description="该任务暂无调整记录"
            />
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-empty v-else-if="!loading" :image-size="80" description="加载失败或任务不存在" />
    </div>
  </el-dialog>
  <FilePreviewDrawer v-model="previewVisible" :file="previewFile" />
</template>

<style scoped>
:global(.task-detail-dialog) {
  display: flex;
  flex-direction: column;
  max-height: 85vh;
  margin: 0;
  border-radius: 10px;
}

:global(.task-detail-dialog .el-dialog__body) {
  min-height: 0;
  overflow-y: auto;
}

@media (max-width: 768px) {
  :global(.task-detail-dialog) {
    max-height: calc(100dvh - 32px);
  }
}

.history-alert {
  margin-bottom: var(--space-3);
}

.detail-desc {
  margin-bottom: var(--space-4);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 var(--space-3);
}

.rate-text {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.empty-text {
  color: var(--el-text-color-placeholder);
}

.submission-list {
  display: grid;
  gap: var(--space-2);
}

.submission-record {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 12px;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.submission-record.is-active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.submission-record small {
  grid-column: 1 / -1;
  color: var(--el-text-color-secondary);
}

.submission-materials {
  margin-top: var(--space-4);
}

.material-list {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.material-list .el-button {
  max-width: 100%;
  height: auto;
  min-height: 32px;
  margin: 0;
  white-space: normal;
  text-align: left;
}

.history-timeline {
  padding-left: var(--space-1);
}

.history-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.history-type {
  font-weight: 600;
  font-size: 13px;
}

.history-line {
  margin-top: 2px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.history-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
