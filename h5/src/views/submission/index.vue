<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import PageState from '@/components/PageState.vue'
import MetricValueInput from '@/components/MetricValueInput.vue'
import FileUploader from '@/components/FileUploader.vue'
import TaskDetailSheet from '@/components/TaskDetailSheet.vue'
import { getTask } from '@/api/task'
import {
  createSubmission,
  listSubmissions,
  submitSubmission,
  updateSubmission,
  withdrawSubmission,
} from '@/api/submission'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { ApiError } from '@/utils/request'
import { formatGrowth, formatPercent, formatDateTime } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import {
  buildMetricValue,
  computeRatio,
  formatMetricValue,
  parseSubmissionRemark,
  projectAnnualCompletionRate,
  serializeSubmissionRemark,
} from '@/utils/submission'
import { canWriteTask, formatDeadline, primaryTaskIndicator, progressPercentage } from '@/utils/task'
import { CALIBER_INPUT_FIELDS } from '@/utils/submission'
import type { FileInfo } from '@/types/file'
import type { StatCaliber } from '@/types/enums'
import type { MetricValue, SubmissionListItem } from '@/types/submission'
import type { TaskDetail, TaskYearItem } from '@/types/task'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const taskId = route.params.taskId as string

const loading = ref(false)
const loadFailed = ref(false)
const task = ref<TaskDetail | null>(null)
const submissions = ref<SubmissionListItem[]>([])
const detailVisible = ref(false)

const isDelegateFill = computed(
  () => userStore.roles.includes('ADMIN') && !userStore.roles.includes('UNIT_OWNER'),
)

const currentYear = new Date().getFullYear()
const yearItem = computed<TaskYearItem | null>(
  () => (task.value?.years ?? []).find((item) => item.year === currentYear) ?? null,
)
const year = computed(() => yearItem.value?.year ?? currentYear)
const lockedByError = ref(false)
const yearLocked = computed(() => {
  const status = yearItem.value?.yearStatus
  return lockedByError.value
    || status === 'ACHIEVED'
    || status === 'NOT_ACHIEVED'
    || status === 'MAKEUP_PENDING'
    || status === 'MADE_UP'
})
const primaryIndicator = computed(() => primaryTaskIndicator(task.value))
const caliber = computed<StatCaliber>(() => primaryIndicator.value?.statCaliber ?? 'INCREMENT')
const unitLabel = computed(() => primaryIndicator.value?.unitLabel ?? null)
const deadline = computed(() => formatDeadline(task.value?.deadline))
const booleanPlanYear = computed(() => {
  const hit = (task.value?.years ?? []).find((item) => item.target === true)
  return hit?.year ?? null
})
const prevYearCompleted = computed<number | null>(() => {
  const prev = (task.value?.years ?? []).find((item) => item.year === year.value - 1)
  return typeof prev?.actualValue === 'number' ? prev.actualValue : null
})

const yearSubmissions = computed(() =>
  submissions.value
    .filter((item) => item.year === year.value)
    .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')),
)
const latestSubmission = computed(() => yearSubmissions.value[0] ?? null)
const pendingSubmission = computed(() =>
  latestSubmission.value?.status === 'PENDING_REVIEW' ? latestSubmission.value : null,
)
const editableSubmission = computed(() => {
  const item = latestSubmission.value
  return item && (item.status === 'DRAFT' || item.status === 'REJECTED' || item.status === 'WITHDRAWN')
    ? item
    : null
})
const isHistorical = computed(() => task.value?.isHistorical === true)
const formReadonly = computed(() => pendingSubmission.value !== null || isHistorical.value)
const pendingValueText = computed(() =>
  pendingSubmission.value
    ? formatMetricValue(pendingSubmission.value.value, caliber.value, unitLabel.value)
    : '',
)
const pendingRemarkParts = computed(() => parseSubmissionRemark(pendingSubmission.value?.remark))

// ---------- 表单 ----------
const value = ref<MetricValue>({
  amount: null,
  total: null,
  numerator: null,
  denominator: null,
  percent: null,
  achieved: null,
})
const completionDate = ref('')
const completionDatePickerVisible = ref(false)
const remark = ref('')
const sourceNote = ref('')
const proxyReason = ref('')
const fileIds = ref<string[]>([])
const initialFiles = ref<FileInfo[]>([])
const editingId = ref<string | null>(null)
const editingVersion = ref(0)
const dirty = ref(false)
let initializing = false

function markDirty(): void {
  if (!initializing && !formReadonly.value && !yearLocked.value) dirty.value = true
}

function populate(): void {
  initializing = true
  const target = isHistorical.value ? latestSubmission.value : editableSubmission.value
  editingId.value = isHistorical.value ? null : (target?.id ?? null)
  editingVersion.value = isHistorical.value ? 0 : (target?.version ?? 0)
  const item = target?.value ?? {}
  value.value = {
    amount: item.amount ?? null,
    total: item.total ?? null,
    numerator: item.numerator ?? null,
    denominator: item.denominator ?? null,
    percent: item.percent ?? null,
    achieved: item.achieved ?? null,
  }
  const parts = parseSubmissionRemark(target?.remark)
  completionDate.value = caliber.value === 'BOOLEAN' ? parts.completionDate : ''
  remark.value = parts.remark
  sourceNote.value = target?.sourceNote ?? ''
  proxyReason.value = ''
  fileIds.value = target?.files?.map((file) => file.id) ?? target?.fileIds ?? []
  initialFiles.value = target?.files ?? []
  dirty.value = false
  initializing = false
}

// 实时计算
const projectedTotal = computed(() => {
  const completed = typeof yearItem.value?.actualValue === 'number' ? yearItem.value.actualValue : 0
  return Math.round((completed + (value.value.amount ?? 0)) * 10000) / 10000
})
const projectedRate = computed(() =>
  projectAnnualCompletionRate(
    caliber.value,
    yearItem.value?.target,
    yearItem.value,
    buildMetricValue(caliber.value, value.value),
  ),
)
const projectedExceedsTarget = computed(
  () =>
    primaryIndicator.value?.targetDirection === 'HIGHER_BETTER'
    && projectedRate.value !== null
    && projectedRate.value > 1,
)
const ratioNow = computed(() => computeRatio(value.value.numerator, value.value.denominator))
const ratioTarget = computed(() => (typeof yearItem.value?.target === 'number' ? yearItem.value.target : null))
const ratioAchieved = computed(() => {
  if (ratioNow.value === null || ratioTarget.value === null) return null
  return ratioNow.value >= ratioTarget.value
})
const stockIncrement = computed(() => {
  if (value.value.total === null || value.value.total === undefined || prevYearCompleted.value === null) return null
  return Math.round((value.value.total - prevYearCompleted.value) * 10000) / 10000
})
const growthRateNow = computed(() => {
  if (value.value.total === null || value.value.total === undefined || !prevYearCompleted.value) return null
  return value.value.total / prevYearCompleted.value - 1
})
const percentTarget = computed(() => (typeof yearItem.value?.target === 'number' ? yearItem.value.target : null))
const percentAchieved = computed(() => {
  const stored = value.value.percent
  if (typeof stored !== 'number' || percentTarget.value === null) return null
  return stored >= percentTarget.value
})

// ---------- 加载 ----------
let reloadGeneration = 0
async function reload(): Promise<void> {
  const generation = ++reloadGeneration
  loading.value = true
  loadFailed.value = false
  try {
    const [detail, list] = await Promise.all([getTask(taskId), listSubmissions(taskId)])
    if (generation !== reloadGeneration) return
    task.value = detail
    submissions.value = list
    populate()
  } catch {
    if (generation === reloadGeneration) loadFailed.value = true
  } finally {
    if (generation === reloadGeneration) loading.value = false
  }
}

// ---------- 保存 / 提交 ----------
const saving = ref(false)
const submitting = ref(false)
const withdrawing = ref(false)
const submitError = ref<{ code: number; message: string } | null>(null)

function buildValue(): MetricValue {
  return buildMetricValue(caliber.value, value.value)
}

function validateForSubmit(): string[] {
  const errors: string[] = []
  const v = value.value
  switch (caliber.value) {
    case 'INCREMENT':
      if (v.amount === null || v.amount === undefined) errors.push('请填写本次新增数量')
      break
    case 'STOCK':
    case 'GROWTH':
      if (v.total === null || v.total === undefined) errors.push('请填写当前总量')
      break
    case 'RATIO':
      if (v.numerator === null || v.numerator === undefined) errors.push('请填写分子')
      if (v.denominator === null || v.denominator === undefined) errors.push('请填写分母')
      else if (v.denominator === 0) errors.push('分母不能为 0')
      break
    case 'DIRECT_PERCENT':
      if (v.percent === null || v.percent === undefined) errors.push('请填写百分比')
      if (!sourceNote.value.trim()) errors.push('直填百分比必须填写数据来源说明')
      break
    case 'BOOLEAN':
      if (v.achieved === null || v.achieved === undefined) errors.push('请选择是否完成')
      break
  }
  const required = Math.max(0, task.value?.requiredFileCount ?? 0)
  if (caliber.value === 'DIRECT_PERCENT' && fileIds.value.length === 0) {
    errors.push('直填百分比必须上传至少 1 份佐证材料')
  } else if (fileIds.value.length < required) {
    errors.push(`请至少上传 ${required} 份佐证材料（当前 ${fileIds.value.length} 份）`)
  }
  if (isDelegateFill.value && !proxyReason.value.trim()) {
    errors.push('管理员代填必须填写代填原因')
  }
  return errors
}

async function saveDraft(): Promise<void> {
  if (!canWriteTask(task.value)) return
  const metricValue = buildValue()
  const body = {
    value: metricValue,
    remark: serializeSubmissionRemark(caliber.value, remark.value, completionDate.value),
    sourceNote: sourceNote.value || null,
    fileIds: fileIds.value,
    proxyReason: isDelegateFill.value ? proxyReason.value || null : undefined,
  }
  if (editingId.value) {
    const saved = await updateSubmission(editingId.value, {
      ...body,
      version: editingVersion.value,
    })
    editingVersion.value = saved.version
    editingId.value = saved.id
    return
  }
  const created = await createSubmission(taskId, { year: year.value, ...body })
  editingId.value = created.id
  editingVersion.value = created.version
}

async function handleSaveDraft(): Promise<void> {
  if (!canWriteTask(task.value)) {
    showToast('历史任务只读，不能保存填报')
    return
  }
  if (formReadonly.value || yearLocked.value) return
  saving.value = true
  try {
    await saveDraft()
    dirty.value = false
    showToast('草稿已保存')
    await reload()
  } finally {
    saving.value = false
  }
}

async function handleSubmit(): Promise<void> {
  if (!canWriteTask(task.value)) {
    showToast('历史任务只读，不能提交审核')
    return
  }
  if (formReadonly.value || yearLocked.value) return
  const errors = validateForSubmit()
  if (errors.length > 0) {
    showToast(errors[0])
    return
  }
  submitError.value = null
  try {
    await showConfirmDialog({
      title: '提交审核',
      message: '提交后审核期间不能修改，确认提交审核？',
      confirmButtonText: '确认提交',
    })
  } catch {
    return
  }
  submitting.value = true
  const idempotencyKey = newIdempotencyKey()
  try {
    await saveDraft()
    dirty.value = false
    await submitSubmission(editingId.value!, editingVersion.value, idempotencyKey)
    showToast('已提交审核')
    await reload()
  } catch (error) {
    if (error instanceof ApiError) {
      submitError.value = { code: error.code, message: error.message }
      if (error.code === 60003) lockedByError.value = true
    }
  } finally {
    submitting.value = false
  }
}

async function handleWithdraw(): Promise<void> {
  const target = pendingSubmission.value
  if (!canWriteTask(task.value) || !target || yearLocked.value) return
  try {
    await showConfirmDialog({
      title: '撤回填报',
      message: '撤回后本次填报回到待提交状态，可修改后重新提交。确认撤回？',
      confirmButtonText: '确认撤回',
    })
  } catch {
    return
  }
  withdrawing.value = true
  try {
    await withdrawSubmission(target.id, target.version, newIdempotencyKey())
    showToast('已撤回，可修改后重新提交')
    await reload()
  } finally {
    withdrawing.value = false
  }
}

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await showConfirmDialog({
      title: '离开页面',
      message: '有未保存的内容，确定离开？',
      confirmButtonText: '离开',
    })
    return true
  } catch {
    return false
  }
})

onMounted(reload)

function goBack(): void {
  router.push('/workbench')
}

function submissionType(status: string): string {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'PENDING_REVIEW') return 'warning'
  return 'gray'
}
</script>

<template>
  <div class="page page--footer">
    <PageState :loading="loading && !task" :empty="!loading && !task" empty-text="任务加载失败">
      <template v-if="task">
        <div class="page-head">
          <h1>填报 · {{ task.code }}</h1>
          <p class="muted small">{{ primaryIndicator?.name ?? '' }}</p>
          <p class="muted small">按统计口径填报完成值，上传佐证材料后提交审核</p>
        </div>

        <div v-if="!yearItem && !isHistorical" class="empty-state">
          只能填报当前自然年份（{{ currentYear }} 年）的数据
        </div>

        <template v-else>
          <div class="card info-card">
            <div class="task-info">
              <StatusTag kind="StatCaliber" :value="caliber" />
              <span v-if="unitLabel" class="small">单位：{{ unitLabel }}</span>
              <span class="small">{{ year }} 年目标 {{ formatTargetValue(yearItem?.target ?? null, caliber) }}</span>
              <span class="small">已认定 {{ formatTargetValue(yearItem?.actualValue ?? null, caliber) }}</span>
              <span class="small" :class="deadline.urgent ? 'text-warning' : ''">截止 {{ deadline.text }}</span>
              <StatusTag v-if="yearItem" kind="TaskYearStatus" :value="yearItem.yearStatus" />
            </div>
            <div class="progress-line">
              <ProgressBar :rate="yearItem?.completionRate" :height="8" show-text />
            </div>
            <div class="row" style="margin-top: 8px">
              <van-button size="mini" @click="detailVisible = true">查看任务详情</van-button>
              <van-button size="mini" plain type="primary" @click="router.push('/workbench')">返回工作台</van-button>
            </div>
          </div>

          <div v-if="isHistorical" class="info-box">历史任务只读，可查看填报内容、佐证材料和审核记录</div>
          <div v-if="yearLocked" class="warn-box">{{ year }} 年度已核算锁定，数据只读；如需修改请联系平台管理员解锁</div>
          <div v-if="pendingSubmission" class="warn-box">
            当前填报正在审核中，审核期间不能修改
            <van-button
              v-if="!isHistorical && !yearLocked"
              size="mini"
              type="warning"
              plain
              :loading="withdrawing"
              class="withdraw-btn"
              @click="handleWithdraw"
            >撤回</van-button>
          </div>
          <div v-if="submitError" class="error-box">{{ submitError.message }}（{{ submitError.code }}）</div>
          <div v-if="projectedExceedsTarget" class="warn-box">
            按已认定数据和本次填报，预计完成率 {{ formatPercent(projectedRate) }}，提交后将超过年度目标；请调整填报值，最终以后端校验为准（60007）
          </div>

          <div class="card">
            <template v-if="formReadonly && pendingSubmission">
              <div class="section-title">本次填报内容（审核中）</div>
              <div class="kv-list">
                <div class="kv"><span class="kv__label">填报值</span><span class="kv__value">{{ pendingValueText }}</span></div>
                <div v-if="pendingSubmission.sourceNote" class="kv"><span class="kv__label">数据来源</span><span class="kv__value">{{ pendingSubmission.sourceNote }}</span></div>
                <div v-if="pendingRemarkParts.completionDate" class="kv"><span class="kv__label">完成日期</span><span class="kv__value">{{ pendingRemarkParts.completionDate }}</span></div>
                <div class="kv"><span class="kv__label">情况说明</span><span class="kv__value">{{ pendingRemarkParts.remark || '—' }}</span></div>
              </div>
            </template>

            <template v-else>
              <MetricValueInput
                v-model="value"
                :caliber="caliber"
                :unit-label="unitLabel"
                :disabled="formReadonly || yearLocked"
                @update:model-value="markDirty"
              />

              <div v-if="caliber === 'INCREMENT' && value.amount !== null" class="calc-hint">
                提交后累计 {{ projectedTotal }} {{ unitLabel ?? '' }}，预计完成率 {{ formatPercent(projectedRate) }}
              </div>
              <div v-if="caliber === 'STOCK' && stockIncrement !== null" class="calc-hint">
                较上年增量 {{ stockIncrement }}
              </div>
              <div v-if="caliber === 'GROWTH'" class="calc-hint">
                增长率（系统计算）：{{ growthRateNow === null ? '—（缺少上年总量）' : formatGrowth(growthRateNow) }}
              </div>
              <div v-if="caliber === 'RATIO'" class="calc-hint">
                占比：{{ ratioNow === null ? '—' : formatPercent(ratioNow) }}
                <span v-if="ratioAchieved === true" class="text-success"> · 达到目标</span>
                <span v-else-if="ratioAchieved === false" class="text-danger"> · 未达目标</span>
              </div>
              <div v-if="caliber === 'DIRECT_PERCENT'" class="calc-hint">
                目标 {{ formatTargetValue(percentTarget, caliber) }}
                <span v-if="percentAchieved === true" class="text-success"> · 达到目标</span>
                <span v-else-if="percentAchieved === false" class="text-danger"> · 未达目标</span>
              </div>

              <div v-if="caliber === 'DIRECT_PERCENT'" class="field">
                <label>数据来源说明<span class="required">*</span></label>
                <van-field
                  v-model="sourceNote"
                  type="textarea"
                  rows="2"
                  maxlength="500"
                  show-word-limit
                  :disabled="formReadonly || yearLocked"
                  placeholder="直填百分比必须填写数据来源（必填，≤ 500 字）"
                  @update:model-value="markDirty"
                />
              </div>

              <div v-if="caliber === 'BOOLEAN'" class="field">
                <label>完成日期</label>
                <van-field
                  readonly
                  is-link
                  :model-value="completionDate"
                  placeholder="选择完成日期（选填）"
                  :disabled="formReadonly || yearLocked"
                  @click="completionDatePickerVisible = true"
                />
              </div>

              <div class="field">
                <label>完成情况说明</label>
                <van-field
                  v-model="remark"
                  type="textarea"
                  rows="3"
                  maxlength="2000"
                  show-word-limit
                  :disabled="formReadonly || yearLocked"
                  placeholder="补充说明本次填报情况（选填）"
                  @update:model-value="markDirty"
                />
              </div>

              <div v-if="isDelegateFill" class="field">
                <label>代填原因<span class="required">*</span></label>
                <van-field
                  v-model="proxyReason"
                  type="textarea"
                  rows="2"
                  maxlength="500"
                  show-word-limit
                  :disabled="formReadonly || yearLocked"
                  placeholder="管理员代填属于敏感操作，必须填写原因"
                  @update:model-value="markDirty"
                />
              </div>
            </template>

            <div class="divider" />
            <div class="section-title">
              佐证材料
              <span v-if="primaryIndicator?.evidenceRequirement" class="small muted">
                （要求：{{ primaryIndicator.evidenceRequirement }}）
              </span>
            </div>
            <p v-if="!formReadonly && !yearLocked" class="small muted material-hint">
              最少 {{ task.requiredFileCount ?? 0 }} 份{{ caliber === 'DIRECT_PERCENT' && !task.requiredFileCount ? '（直填百分比至少 1 份）' : '' }}
            </p>
            <FileUploader
              :key="`${editingId ?? 'new'}-${formReadonly}-${yearLocked}`"
              v-model="fileIds"
              :initial-files="initialFiles"
              :disabled="formReadonly || yearLocked"
              @update:model-value="markDirty"
            />
          </div>

          <div class="card">
            <div class="card-title">
              <span>填报记录</span>
              <span class="small muted">待审 {{ yearSubmissions.filter((item) => item.status === 'PENDING_REVIEW').length }} 条</span>
            </div>
            <div v-if="yearSubmissions.length === 0" class="empty-state">本年度还没有填报记录</div>
            <div v-else class="timeline">
              <div v-for="item in yearSubmissions" :key="item.id" class="timeline-item">
                <div class="row--between">
                  <strong>{{ formatMetricValue(item.value, caliber, unitLabel) }}</strong>
                  <StatusTag kind="SubmissionStatus" :value="item.status" />
                </div>
                <div class="small muted">
                  {{ formatDateTime(item.submittedAt ?? item.createdAt) }}
                  <span v-if="item.isProxy"> · 管理员代填{{ item.proxyReason ? `（${item.proxyReason}）` : '' }}</span>
                </div>
                <div v-if="item.status === 'APPROVED' && item.approvedValue" class="small">
                  认定 {{ formatMetricValue(item.approvedValue, caliber, unitLabel) }}
                </div>
                <div v-for="review in item.reviews ?? []" :key="review.id" class="review-line">
                  <span class="small muted">{{ review.reviewer?.name ?? '审核人' }}</span>
                  <span class="chip" :class="review.result === 'APPROVED' ? 'chip--success' : 'chip--danger'">
                    {{ review.result === 'APPROVED' ? '通过' : '驳回' }}
                  </span>
                  <span v-if="review.isProxy" class="chip chip--warning">代</span>
                  <span v-if="review.comment" class="small">“{{ review.comment }}”</span>
                </div>
              </div>
            </div>
          </div>
        </template>
      </template>
    </PageState>

    <div v-if="task && yearItem && !formReadonly && !yearLocked" class="sticky-footer">
      <div class="sticky-footer__row">
        <van-button :disabled="saving || submitting" @click="goBack">取消</van-button>
        <van-button :loading="saving" :disabled="submitting" @click="handleSaveDraft">存草稿</van-button>
        <van-button type="primary" :loading="submitting" :disabled="saving" @click="handleSubmit">提交审核</van-button>
      </div>
    </div>

    <TaskDetailSheet v-model="detailVisible" :task-id="taskId" />

    <van-popup v-model:show="completionDatePickerVisible" position="bottom" round>
      <van-date-picker
        :min-date="new Date(2000, 0, 1)"
        :max-date="new Date(2100, 11, 31)"
        @confirm="(value: { selectedValues: string[] }) => {
          completionDate = value.selectedValues.join('-')
          completionDatePickerVisible = false
          markDirty()
        }"
        @cancel="completionDatePickerVisible = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.page-head {
  margin-bottom: 10px;
}
.page-head h1 {
  margin: 0;
  font-size: 20px;
  line-height: 27px;
}
.page-head p {
  margin: 3px 0 0;
}
.info-card {
  padding: 12px;
}
.task-info {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.progress-line {
  margin-top: 8px;
}
.info-box,
.warn-box,
.error-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 18px;
}
.info-box {
  background: #eef3fc;
  color: #4a5f85;
}
.warn-box {
  background: #fdf3e5;
  color: #8f5d16;
}
.error-box {
  background: #fdecec;
  color: var(--danger);
}
.withdraw-btn {
  margin-left: 8px;
}
.calc-hint {
  margin: 6px 0 10px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
}
.field {
  margin-bottom: 10px;
}
.field label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.required {
  color: var(--danger);
}
.field :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.material-hint {
  margin: -4px 0 8px;
}
.timeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.timeline-item {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.review-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}
</style>
