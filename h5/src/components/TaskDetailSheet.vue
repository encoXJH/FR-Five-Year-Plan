<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import StatusTag from '@/components/StatusTag.vue'
import AttachmentList from '@/components/AttachmentList.vue'
import PageState from '@/components/PageState.vue'
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
  initialYear?: number | null
  initialSubmissionId?: string | null
  initialTab?: 'info' | 'submissions' | 'adjustments'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const userStore = useUserStore()
const loading = ref(false)
const task = ref<TaskDetail | null>(null)
const activeTab = ref('info')

const primaryIndicator = computed(() => primaryTaskIndicator(task.value))
const caliber = computed<StatCaliber>(() => primaryIndicator.value?.statCaliber ?? 'INCREMENT')

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

const submissions = ref<SubmissionListItem[]>([])
const submissionDetail = ref<SubmissionDetail | null>(null)
const selectedSubmissionId = ref<string | null>(null)
const submissionLoading = ref(false)
const submissionLoadFailed = ref(false)
let submissionRequestSequence = 0

const visibleSubmissions = computed(() => {
  if (props.initialYear == null) return submissions.value
  return submissions.value.filter((item) => item.year === props.initialYear)
})

async function openSubmission(item: SubmissionListItem): Promise<void> {
  const sequence = ++submissionRequestSequence
  selectedSubmissionId.value = item.id
  submissionDetail.value = null
  submissionLoadFailed.value = false
  submissionLoading.value = true
  try {
    const detail = await getSubmission(item.id)
    if (sequence !== submissionRequestSequence || !visible.value) return
    submissionDetail.value = detail
  } catch {
    if (sequence === submissionRequestSequence && visible.value) submissionLoadFailed.value = true
  } finally {
    if (sequence === submissionRequestSequence) submissionLoading.value = false
  }
}

function submissionRoundLabel(revisionNo: number | null | undefined): string {
  return typeof revisionNo === 'number' && revisionNo > 0 ? `第 ${revisionNo} 次提交` : '尚未提交'
}

const submissionEmptyText = computed(() =>
  props.initialYear == null
    ? '该任务暂无提交记录'
    : `${props.initialYear} 年暂无提交记录，请返回工作台点击「填报」`,
)

let loadSequence = 0

async function load(sequence: number): Promise<void> {
  const taskId = props.taskId
  if (!taskId) return
  loading.value = true
  try {
    const [detail, history, submissionItems] = await Promise.all([
      getTask(taskId),
      listTaskAdjustments(taskId).catch(() => [] as AdjustmentListItem[]),
      listSubmissions(taskId).catch(() => [] as SubmissionListItem[]),
    ])
    if (sequence !== loadSequence || !visible.value || props.taskId !== taskId) return
    task.value = detail
    adjustments.value = history
    submissions.value = submissionItems
    adjustmentsLoaded.value = true
    if (activeTab.value === 'submissions') {
      const latest = visibleSubmissions.value.find((item) => item.id === props.initialSubmissionId)
        ?? visibleSubmissions.value[0]
      if (latest) void openSubmission(latest)
    }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

watch(visible, (value) => {
  loadSequence += 1
  submissionRequestSequence += 1
  if (value) {
    task.value = null
    adjustments.value = []
    submissions.value = []
    submissionDetail.value = null
    selectedSubmissionId.value = null
    submissionLoading.value = false
    submissionLoadFailed.value = false
    adjustmentsLoaded.value = false
    activeTab.value = props.initialTab ?? 'info'
    void load(loadSequence)
  } else {
    loading.value = false
    submissionLoading.value = false
  }
})

function selectTab(tab: string): void {
  activeTab.value = tab
  if (tab === 'submissions' && !selectedSubmissionId.value && visibleSubmissions.value.length > 0) {
    void openSubmission(visibleSubmissions.value[0])
  }
}

const detailFiles = computed<FileInfo[]>(() => submissionDetail.value?.files ?? [])
</script>

<template>
  <van-popup
    :show="modelValue"
    position="bottom"
    round
    :style="{ height: '88vh' }"
    @update:show="emit('update:modelValue', $event)"
  >
    <div class="task-sheet">
      <div class="sheet-head">
        <strong>{{ task ? `任务详情 · ${task.code}` : '任务详情' }}</strong>
        <button type="button" class="close-btn" @click="emit('update:modelValue', false)">
          <van-icon name="cross" size="18" />
        </button>
      </div>

      <div class="sheet-body">
        <PageState :loading="loading && !task" :empty="!loading && !task" empty-text="加载失败或任务不存在">
          <template v-if="task">
            <div v-if="task.isHistorical" class="info-box">历史任务只读，可查看年度目标、完成值和调整记录</div>

            <div class="sheet-tabs">
              <button
                v-for="tab in [
                  { key: 'info', label: '任务信息' },
                  { key: 'submissions', label: '提交记录' },
                  { key: 'adjustments', label: '调整历史' },
                ]"
                :key="tab.key"
                type="button"
                class="sheet-tab"
                :class="{ 'is-active': activeTab === tab.key }"
                @click="selectTab(tab.key)"
              >{{ tab.label }}</button>
            </div>

            <div v-if="activeTab === 'info'" class="tab-pane">
              <div class="kv-list">
                <div class="kv"><span class="kv__label">任务编号</span><span class="kv__value">{{ task.code }}</span></div>
                <div class="kv"><span class="kv__label">任务状态</span><span class="kv__value"><StatusTag kind="TaskStatus" :value="task.status" /></span></div>
                <div class="kv"><span class="kv__label">所属指标</span><span class="kv__value">{{ primaryIndicator ? `${primaryIndicator.code} ${primaryIndicator.name}` : '—' }}</span></div>
                <div class="kv"><span class="kv__label">执行单元</span><span class="kv__value">{{ task.unit ? `${task.unit.code} ${task.unit.name}` : '—' }}</span></div>
                <div class="kv"><span class="kv__label">学院负责人</span><span class="kv__value">{{ task.owner?.name ?? '—' }}</span></div>
                <div class="kv"><span class="kv__label">统计口径</span><span class="kv__value"><StatusTag v-if="primaryIndicator?.statCaliber" kind="StatCaliber" :value="primaryIndicator.statCaliber" /><template v-else>—</template></span></div>
                <div class="kv"><span class="kv__label">五年目标</span><span class="kv__value">{{ formatTargetValue(task.totalTarget ?? null, caliber) }}{{ primaryIndicator?.unitLabel ? ` ${primaryIndicator.unitLabel}` : '' }}</span></div>
              </div>

              <h3 class="section-title">年度目标与完成</h3>
              <div class="year-cards">
                <div v-for="row in task.years ?? []" :key="row.year" class="year-card">
                  <div class="row--between">
                    <strong>{{ row.year }} 年</strong>
                    <StatusTag kind="TaskYearStatus" :value="row.yearStatus" />
                  </div>
                  <div class="year-nums">
                    <span>目标 <strong>{{ formatTargetValue(row.target, caliber) }}</strong></span>
                    <span>完成 <strong>{{ formatTargetValue(row.actualValue, caliber) }}</strong></span>
                  </div>
                  <div class="row">
                    <div class="progress progress--sm grow">
                      <div
                        class="progress__bar"
                        :class="row.completionRate !== null && row.completionRate >= 1 ? 'is-complete' : 'is-partial'"
                        :style="{ width: `${progressPercentage(row.completionRate)}%` }"
                      />
                    </div>
                    <span class="small muted">{{ formatPercent(row.completionRate) }}</span>
                  </div>
                </div>
              </div>
            </div>

            <div v-else-if="activeTab === 'submissions'" class="tab-pane">
              <div v-if="visibleSubmissions.length > 0" class="submission-list">
                <button
                  v-for="item in visibleSubmissions"
                  :key="item.id"
                  type="button"
                  class="submission-record"
                  :class="{ 'is-active': selectedSubmissionId === item.id }"
                  @click="openSubmission(item)"
                >
                  <div class="row--between">
                    <span>{{ item.year }} 年 · {{ submissionRoundLabel(item.revisionNo) }}</span>
                    <StatusTag kind="SubmissionStatus" :value="item.status" />
                  </div>
                  <small class="muted">{{ formatDateTime(item.submittedAt ?? item.createdAt) }} · 材料 {{ item.fileCount ?? 0 }} 份</small>
                </button>
              </div>
              <div v-else-if="!loading" class="empty-state">{{ submissionEmptyText }}</div>

              <div v-if="selectedSubmissionId" class="submission-materials">
                <div v-if="submissionLoading" class="skeleton-block">加载提交材料…</div>
                <template v-else-if="submissionDetail">
                  <h3 class="section-title">提交材料（{{ detailFiles.length }} 份）</h3>
                  <AttachmentList :files="detailFiles" empty-text="本次提交未上传材料" />
                </template>
                <div v-else-if="submissionLoadFailed" class="empty-state">提交详情加载失败，请点击上方记录重试</div>
              </div>
            </div>

            <div v-else class="tab-pane">
              <div v-if="adjustments.length > 0" class="history-list">
                <div v-for="item in adjustments" :key="item.id" class="history-item">
                  <div class="row--between">
                    <strong>{{ enumLabel('AdjustmentType', item.type, userStore.enums) }}</strong>
                    <StatusTag kind="AdjustmentStatus" :value="item.status" />
                  </div>
                  <div class="small">{{ adjustmentSummary(item) }}</div>
                  <div v-if="transferLine(item)" class="small">{{ transferLine(item) }}</div>
                  <div class="small muted">
                    {{ formatDateTime(item.createdAt) }} · 申请人 {{ item.applicant?.name ?? '—' }}
                    <template v-if="item.reason"> · {{ item.reason }}</template>
                  </div>
                </div>
              </div>
              <div v-else-if="adjustmentsLoaded" class="empty-state">该任务暂无调整记录</div>
            </div>
          </template>
        </PageState>
      </div>
    </div>
  </van-popup>
</template>

<style scoped>
.task-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  font-size: 16px;
}
.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text-secondary);
}
.sheet-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px calc(16px + var(--safe-bottom));
}
.info-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #eef3fc;
  color: #4a5f85;
  font-size: 12px;
}
.sheet-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
  padding: 4px;
  border-radius: 10px;
  background: #f1f4f9;
}
.sheet-tab {
  flex: 1;
  min-height: 36px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
}
.sheet-tab.is-active {
  background: #fff;
  color: var(--brand);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(31, 45, 61, 0.08);
}
.tab-pane {
  min-height: 120px;
}
.section-title {
  margin-top: 16px;
}
.year-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.year-card {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.year-nums {
  display: flex;
  gap: 16px;
  margin: 6px 0;
  color: var(--text-secondary);
  font-size: 12px;
}
.submission-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.submission-record {
  width: 100%;
  min-height: 56px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  color: var(--text);
  text-align: left;
}
.submission-record.is-active {
  border-color: var(--brand);
  background: #f5f8ff;
}
.submission-record small {
  display: block;
  margin-top: 4px;
  font-size: 11px;
}
.submission-materials {
  margin-top: 14px;
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.history-item {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.history-item .small {
  margin-top: 4px;
}
</style>
