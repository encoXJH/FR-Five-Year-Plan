<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import TaskDetailDrawer from '@/views/workbench/components/TaskDetailDrawer.vue'
import MaterialUploader from './components/MaterialUploader.vue'
import SubmissionTimeline from './components/SubmissionTimeline.vue'
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
import { formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import { percentToDisplay, percentToStore } from '@/utils/allocation'
import { buildMetricValue, computeRatio, formatMetricValue } from '@/utils/submission'
import { canWriteTask, formatDeadline, progressPercentage } from '@/utils/task'
import type { FileInfo } from '@/types/file'
import type { StatCaliber } from '@/types/enums'
import type { SubmissionListItem } from '@/types/submission'
import type { TaskDetail, TaskYearItem } from '@/types/task'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const taskId = route.params.taskId as string
const queryYear = route.query.year ? Number(route.query.year) : null

const loading = ref(false)
const loadFailed = ref(false)
const task = ref<TaskDetail | null>(null)
const submissions = ref<SubmissionListItem[]>([])

/** 管理员代填（非学院负责人）：提交时必须填写代填原因（API 第 10 章敏感操作） */
const isDelegateFill = computed(
  () => userStore.roles.includes('ADMIN') && !userStore.roles.includes('UNIT_OWNER'),
)

// ---------- 年度上下文 ----------
const currentYear = new Date().getFullYear()

const yearItem = computed<TaskYearItem | null>(() => {
  const years = task.value?.years ?? []
  if (queryYear !== null) return years.find((item) => item.year === queryYear) ?? null
  return (
    years.find((item) => item.yearStatus === 'IN_PROGRESS') ??
    years.find((item) => item.year === currentYear) ??
    years[0] ??
    null
  )
})

const year = computed(() => yearItem.value?.year ?? queryYear ?? currentYear)

const yearNotActivated = computed(() => yearItem.value?.yearStatus === 'NOT_ACTIVATED')

/** 提交遇 60003 时置位：年度已锁定（整页只读） */
const lockedByError = ref(false)

/**
 * 年度已核算锁定：年度核算后 task_year 进入 ACHIEVED / NOT_ACHIEVED / MAKEUP_PENDING / MADE_UP，
 * 或提交时后端返回 60003
 */
const yearLocked = computed(() => {
  const status = yearItem.value?.yearStatus
  return (
    lockedByError.value ||
    status === 'ACHIEVED' ||
    status === 'NOT_ACHIEVED' ||
    status === 'MAKEUP_PENDING' ||
    status === 'MADE_UP'
  )
})

const caliber = computed<StatCaliber>(() => task.value?.indicator?.statCaliber ?? 'INCREMENT')
const unitLabel = computed(() => task.value?.indicator?.unitLabel ?? null)

/** 填报截止日（任务详情 deadline 字段，YYYY-MM-DD） */
const deadline = computed(() => formatDeadline(task.value?.deadline))
const deadlineText = computed(() => deadline.value.text)

/** 是否型计划完成年 */
const booleanPlanYear = computed(() => {
  const hit = (task.value?.years ?? []).find((item) => item.target === true)
  return hit?.year ?? null
})

/** 上一年已完成（STOCK 增量 / GROWTH 增长率的实时对比） */
const prevYearCompleted = computed<number | null>(() => {
  const prev = (task.value?.years ?? []).find((item) => item.year === year.value - 1)
  return typeof prev?.actualValue === 'number' ? prev.actualValue : null
})

// ---------- 填报记录 ----------
const yearSubmissions = computed(() =>
  submissions.value
    .filter((item) => item.year === year.value)
    .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')),
)

const latestSubmission = computed(() => yearSubmissions.value[0] ?? null)

/** 存在待审核填报：表单只读 + 可撤回（P08 状态约束） */
const pendingSubmission = computed(() =>
  latestSubmission.value?.status === 'PENDING_REVIEW' ? latestSubmission.value : null,
)

/** 可继续编辑的填报（草稿 / 被驳回 / 已撤回，后端契约：三者均可 PATCH 再提交） */
const editableSubmission = computed(() => {
  const item = latestSubmission.value
  return item && (item.status === 'DRAFT' || item.status === 'REJECTED' || item.status === 'WITHDRAWN')
    ? item
    : null
})

const isHistorical = computed(() => task.value?.isHistorical === true)
const formReadonly = computed(() => pendingSubmission.value !== null || isHistorical.value)

/** 审核中只读摘要的填报值文本 */
const pendingValueText = computed(() =>
  pendingSubmission.value
    ? formatMetricValue(pendingSubmission.value.value, caliber.value, unitLabel.value)
    : '',
)

// ---------- 表单 ----------
const form = reactive({
  amount: undefined as number | undefined,
  total: undefined as number | undefined,
  numerator: undefined as number | undefined,
  denominator: undefined as number | undefined,
  /** 百分比显示值（%，提交时 ÷100） */
  percent: undefined as number | undefined,
  achieved: null as boolean | null,
  /** BOOLEAN 完成日期（YYYY-MM-DD）；后端 CreateSubmissionRequest 暂无此字段，仅展示不提交 */
  completionDate: '' as string,
  remark: '',
  sourceNote: '',
  reason: '',
})

const fileIds = ref<string[]>([])
const initialFiles = ref<FileInfo[]>([])
const editingId = ref<string | null>(null)
const editingVersion = ref(0)
const dirty = ref(false)

function markDirty(): void {
  if (formReadonly.value || yearLocked.value) return
  dirty.value = true
}

function populate(): void {
  const target = isHistorical.value ? latestSubmission.value : editableSubmission.value
  editingId.value = isHistorical.value ? null : (target?.id ?? null)
  editingVersion.value = isHistorical.value ? 0 : (target?.version ?? 0)
  const value = target?.value ?? {}
  form.amount = value.amount ?? undefined
  form.total = value.total ?? undefined
  form.numerator = value.numerator ?? undefined
  form.denominator = value.denominator ?? undefined
  form.percent = percentToDisplay(value.percent)
  form.achieved = value.achieved ?? null
  form.remark = target?.remark ?? ''
  form.sourceNote = target?.sourceNote ?? ''
  form.reason = ''
  fileIds.value = target?.files?.map((file) => file.id) ?? target?.fileIds ?? []
  initialFiles.value = target?.files ?? []
  dirty.value = false
}

// ---------- 实时计算展示 ----------
const numberOf = (v: number | boolean | null | undefined): number | null =>
  typeof v === 'number' ? v : null

/** INCREMENT：提交后累计 = 已认定 + 本次新增 */
const projectedTotal = computed(() => {
  const completed = numberOf(yearItem.value?.actualValue) ?? 0
  return completed + (form.amount ?? 0)
})

const projectedRate = computed(() => {
  const target = numberOf(yearItem.value?.target)
  if (!target || target <= 0) return null
  return projectedTotal.value / target
})

/** RATIO：占比 = 分子 ÷ 分母 */
const ratioNow = computed(() => computeRatio(form.numerator ?? null, form.denominator ?? null))

const ratioTarget = computed(() => numberOf(yearItem.value?.target))

const ratioAchieved = computed(() => {
  if (ratioNow.value === null || ratioTarget.value === null) return null
  return ratioNow.value >= ratioTarget.value
})

/** STOCK：较上年增量 */
const stockIncrement = computed(() => {
  if (form.total === undefined || prevYearCompleted.value === null) return null
  return form.total - prevYearCompleted.value
})

/** GROWTH：增长率 = 本年总量 ÷ 上年总量 − 1 */
const growthRateNow = computed(() => {
  if (form.total === undefined || !prevYearCompleted.value) return null
  return form.total / prevYearCompleted.value - 1
})

const percentTarget = computed(() => numberOf(yearItem.value?.target))

const percentAchieved = computed(() => {
  const stored = percentToStore(form.percent)
  if (stored === null || percentTarget.value === null) return null
  return stored >= percentTarget.value
})

// ---------- 加载 ----------
async function reload(): Promise<void> {
  loading.value = true
  loadFailed.value = false
  try {
    const [detail, list] = await Promise.all([getTask(taskId), listSubmissions(taskId)])
    task.value = detail
    submissions.value = list
    populate()
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

// ---------- 保存 / 提交 / 撤回 ----------
const saving = ref(false)
const submitting = ref(false)
const withdrawing = ref(false)
const submitError = ref<{ code: number; message: string } | null>(null)

function buildValue() {
  return buildMetricValue(caliber.value, {
    amount: form.amount ?? null,
    total: form.total ?? null,
    numerator: form.numerator ?? null,
    denominator: form.denominator ?? null,
    percent: percentToStore(form.percent),
    achieved: form.achieved,
  })
}

async function saveDraft(): Promise<void> {
  if (!canWriteTask(task.value)) return
  const value = buildValue()
  if (editingId.value) {
    const saved = await updateSubmission(editingId.value, {
      value,
      remark: form.remark || null,
      sourceNote: form.sourceNote || null,
      fileIds: fileIds.value,
      proxyReason: isDelegateFill.value ? form.reason || null : undefined,
      version: editingVersion.value,
    })
    editingVersion.value = saved.version
    return
  }
  const created = await createSubmission(taskId, {
    year: year.value,
    value,
    remark: form.remark || null,
    sourceNote: form.sourceNote || null,
    fileIds: fileIds.value,
    proxyReason: isDelegateFill.value ? form.reason || null : undefined,
  })
  editingId.value = created.id
  editingVersion.value = created.version
}

async function handleSaveDraft(): Promise<void> {
  if (!canWriteTask(task.value)) {
    ElMessage.warning('历史任务只读，不能保存填报')
    return
  }
  if (formReadonly.value || yearLocked.value) return
  saving.value = true
  try {
    await saveDraft()
    dirty.value = false
    ElMessage.success('草稿已保存')
    await reload()
  } finally {
    saving.value = false
  }
}

function validateForSubmit(): string[] {
  const errors: string[] = []
  switch (caliber.value) {
    case 'INCREMENT':
      if (form.amount === undefined) errors.push('请填写本次新增数量')
      break
    case 'STOCK':
    case 'GROWTH':
      if (form.total === undefined) errors.push('请填写当前总量')
      break
    case 'RATIO':
      if (form.numerator === undefined) errors.push('请填写分子')
      if (form.denominator === undefined) errors.push('请填写分母')
      else if (form.denominator === 0) errors.push('分母不能为 0')
      break
    case 'DIRECT_PERCENT':
      if (form.percent === undefined) errors.push('请填写百分比')
      if (!form.sourceNote.trim()) errors.push('直填百分比必须填写数据来源说明')
      break
    case 'BOOLEAN':
      if (form.achieved === null) errors.push('请选择是否完成')
      break
  }
  if (isDelegateFill.value && !form.reason.trim()) {
    errors.push('管理员代填必须填写代填原因')
  }
  return errors
}

async function handleSubmit(): Promise<void> {
  if (!canWriteTask(task.value)) {
    ElMessage.warning('历史任务只读，不能提交审核')
    return
  }
  if (formReadonly.value || yearLocked.value) return
  const errors = validateForSubmit()
  if (errors.length > 0) {
    ElMessage.error(errors[0])
    return
  }
  submitError.value = null
  try {
    await ElMessageBox.confirm(
      '提交后审核期间不能修改，确认提交审核？',
      '提交审核',
      { confirmButtonText: '确认提交', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  submitting.value = true
  const idempotencyKey = newIdempotencyKey()
  try {
    await saveDraft()
    dirty.value = false
    await submitSubmission(editingId.value!, editingVersion.value, idempotencyKey)
    ElMessage.success('已提交审核')
    await reload()
  } catch (error) {
    // 60002 材料不足 / 60006 口径不匹配 / 50004 年度未激活 / 60003 年度锁定：页内清晰展示
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
  if (!canWriteTask(task.value)) {
    ElMessage.warning('历史任务只读，不能撤回填报')
    return
  }
  if (!target || yearLocked.value) return
  try {
    await ElMessageBox.confirm(
      '撤回后本次填报回到待提交状态，可修改后重新提交。确认撤回？',
      '撤回填报',
      { confirmButtonText: '确认撤回', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  withdrawing.value = true
  try {
    await withdrawSubmission(target.id, target.version, newIdempotencyKey())
    ElMessage.success('已撤回，可修改后重新提交')
    await reload()
  } finally {
    withdrawing.value = false
  }
}

// ---------- 其他 ----------
const detailVisible = ref(false)

function goBack(): void {
  router.back()
}

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('有未保存的内容，确定离开？', '离开页面', {
      type: 'warning',
      confirmButtonText: '离开',
      cancelButtonText: '取消',
    })
    return true
  } catch {
    return false
  }
})

onMounted(reload)
</script>

<template>
  <div class="submit-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">
          填报 · {{ task?.code ?? '' }} {{ task?.indicator?.name ?? '' }}
        </h1>
        <p class="page-subtitle">按统计口径填报完成值，上传佐证材料后提交审核</p>
      </div>
      <el-button @click="detailVisible = true">查看任务详情</el-button>
    </div>

    <el-skeleton v-if="loading && !task" :rows="8" animated />
    <el-result v-else-if="loadFailed" icon="error" title="任务加载失败">
      <template #extra>
        <el-button type="primary" @click="reload">重试</el-button>
        <el-button @click="goBack">返回</el-button>
      </template>
    </el-result>

    <template v-else-if="task">
      <el-empty
        v-if="yearNotActivated && !isHistorical"
        :image-size="120"
        :description="`${year} 年度尚未激活，激活后即可填报`"
      >
        <el-button @click="goBack">返回工作台</el-button>
      </el-empty>

      <el-row v-else :gutter="16">
        <el-col :span="16">
          <el-card shadow="never" class="info-card">
            <div class="task-info">
              <StatusTag kind="StatCaliber" :value="caliber" />
              <span v-if="unitLabel" class="info-item">单位：{{ unitLabel }}</span>
              <span class="info-item">
                {{ year }} 年目标 {{ formatTargetValue(yearItem?.target ?? null, caliber) }}
              </span>
              <span class="info-item">
                已认定 {{ formatTargetValue(yearItem?.actualValue ?? null, caliber) }}
              </span>
              <span
                class="info-item"
                :class="{ 'deadline-overdue': deadline.overdue, 'deadline-urgent': deadline.urgent && !deadline.overdue }"
              >
                截止 {{ deadlineText }}
              </span>
              <StatusTag v-if="yearItem" kind="TaskYearStatus" :value="yearItem.yearStatus" />
            </div>
          </el-card>

          <el-alert
            v-if="isHistorical"
            type="info"
            :closable="false"
            class="page-alert"
            title="历史任务只读，可查看填报内容、佐证材料和审核记录"
          />

          <el-alert
            v-if="yearLocked"
            type="warning"
            :closable="false"
            class="page-alert"
            :title="`${year} 年度已核算锁定，数据只读；如需修改请联系平台管理员解锁`"
          />

          <el-alert
            v-if="pendingSubmission"
            type="warning"
            :closable="false"
            class="page-alert"
            title="当前填报正在审核中，审核期间不能修改"
          >
            <el-button
              v-if="!isHistorical && !yearLocked"
              size="small"
              type="warning"
              plain
              :loading="withdrawing"
              @click="handleWithdraw"
            >
              撤回
            </el-button>
          </el-alert>

          <el-alert
            v-if="submitError"
            type="error"
            :closable="false"
            class="page-alert"
            :title="`${submitError.message}（${submitError.code}）`"
          />

          <el-card shadow="never" class="form-card">
            <!-- 审核中：只读摘要（UI 规范 10.3 纯文本展示） -->
            <template v-if="formReadonly && pendingSubmission">
              <h3 class="section-title">本次填报内容（审核中）</h3>
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="填报值">
                  <span class="readonly-value">{{ pendingValueText }}</span>
                </el-descriptions-item>
                <el-descriptions-item v-if="pendingSubmission.sourceNote" label="数据来源说明">
                  {{ pendingSubmission.sourceNote }}
                </el-descriptions-item>
                <el-descriptions-item label="完成情况说明">
                  {{ pendingSubmission.remark ?? '—' }}
                </el-descriptions-item>
              </el-descriptions>
            </template>

            <!-- 可编辑表单：输入项随统计口径切换（P08）；年度锁定后整页只读 -->
            <el-form v-else label-width="130px" class="metric-form" :disabled="formReadonly || yearLocked" @submit.prevent>
              <template v-if="caliber === 'INCREMENT'">
                <el-form-item label="本次新增数量" required>
                  <el-input-number
                    v-model="form.amount"
                    :min="0"
                    :precision="0"
                    controls-position="right"
                    @change="markDirty"
                  />
                  <span v-if="unitLabel" class="field-unit">{{ unitLabel }}</span>
                </el-form-item>
                <el-form-item v-if="form.amount !== undefined" label=" ">
                  <span class="calc-hint">
                    提交后累计 {{ projectedTotal }} {{ unitLabel ?? '' }}，预计完成率
                    {{ projectedRate === null ? '—' : formatPercent(projectedRate) }}
                  </span>
                </el-form-item>
              </template>

              <template v-else-if="caliber === 'STOCK'">
                <el-form-item label="当前总量" required>
                  <el-input-number
                    v-model="form.total"
                    :min="0"
                    :precision="4"
                    controls-position="right"
                    @change="markDirty"
                  />
                  <span v-if="unitLabel" class="field-unit">{{ unitLabel }}</span>
                </el-form-item>
                <el-form-item v-if="stockIncrement !== null" label=" ">
                  <span class="calc-hint">较上年增量 {{ stockIncrement }}</span>
                </el-form-item>
              </template>

              <template v-else-if="caliber === 'GROWTH'">
                <el-form-item label="本年总量" required>
                  <el-input-number
                    v-model="form.total"
                    :min="0"
                    :precision="4"
                    controls-position="right"
                    @change="markDirty"
                  />
                  <span v-if="unitLabel" class="field-unit">{{ unitLabel }}</span>
                </el-form-item>
                <el-form-item label=" ">
                  <span class="calc-hint">
                    增长率（系统计算）：
                    <template v-if="growthRateNow !== null">
                      {{ growthRateNow >= 0 ? '+' : '' }}{{ formatPercent(Math.abs(growthRateNow)) }}
                    </template>
                    <template v-else>—（缺少上年总量，无法计算）</template>
                  </span>
                </el-form-item>
              </template>

              <template v-else-if="caliber === 'RATIO'">
                <el-form-item label="分子" required>
                  <el-input-number
                    v-model="form.numerator"
                    :min="0"
                    :precision="4"
                    controls-position="right"
                    @change="markDirty"
                  />
                </el-form-item>
                <el-form-item label="分母" required>
                  <el-input-number
                    v-model="form.denominator"
                    :min="0"
                    :precision="4"
                    controls-position="right"
                    @change="markDirty"
                  />
                </el-form-item>
                <el-form-item label="占比（系统计算）">
                  <span class="calc-box">
                    {{ ratioNow === null ? '—' : formatPercent(ratioNow) }}
                    <el-tag v-if="ratioAchieved === true" type="success" size="small" effect="plain">达到目标</el-tag>
                    <el-tag v-else-if="ratioAchieved === false" type="danger" size="small" effect="plain">未达目标</el-tag>
                  </span>
                  <span v-if="form.denominator === 0" class="field-error">分母为 0，无法计算占比</span>
                </el-form-item>
              </template>

              <template v-else-if="caliber === 'DIRECT_PERCENT'">
                <el-form-item label="百分比" required>
                  <el-input-number
                    v-model="form.percent"
                    :min="0"
                    :max="100"
                    :precision="1"
                    controls-position="right"
                    @change="markDirty"
                  />
                  <span class="field-unit">%</span>
                  <el-tag v-if="percentAchieved === true" type="success" size="small" effect="plain" class="field-tag">达到目标</el-tag>
                  <el-tag v-else-if="percentAchieved === false" type="danger" size="small" effect="plain" class="field-tag">未达目标</el-tag>
                </el-form-item>
                <el-form-item label="数据来源说明" required>
                  <el-input
                    v-model="form.sourceNote"
                    type="textarea"
                    :rows="2"
                    maxlength="500"
                    show-word-limit
                    placeholder="直填百分比必须填写数据来源（必填，≤ 500 字）"
                    @input="markDirty"
                  />
                </el-form-item>
              </template>

              <template v-else-if="caliber === 'BOOLEAN'">
                <el-form-item :label="`是否完成${booleanPlanYear ? `（计划 ${booleanPlanYear} 年）` : ''}`" required>
                  <el-radio-group v-model="form.achieved" @change="markDirty">
                    <el-radio :value="true">是</el-radio>
                    <el-radio :value="false">否</el-radio>
                  </el-radio-group>
                </el-form-item>
                <!-- 后端 CreateSubmissionRequest 暂无完成日期字段：仅展示记录，不随填报提交 -->
                <el-form-item label="完成日期">
                  <el-date-picker
                    v-model="form.completionDate"
                    type="date"
                    value-format="YYYY-MM-DD"
                    placeholder="选择完成日期（选填）"
                    @change="markDirty"
                  />
                </el-form-item>
              </template>

              <el-form-item label="完成情况说明">
                <el-input
                  v-model="form.remark"
                  type="textarea"
                  :rows="3"
                  maxlength="2000"
                  show-word-limit
                  placeholder="补充说明本次填报情况（选填，≤ 2000 字）"
                  @input="markDirty"
                />
              </el-form-item>

              <el-form-item v-if="isDelegateFill" label="代填原因" required>
                <el-input
                  v-model="form.reason"
                  type="textarea"
                  :rows="2"
                  maxlength="500"
                  show-word-limit
                  placeholder="管理员代填属于敏感操作，必须填写原因（≤ 500 字）"
                  @input="markDirty"
                />
              </el-form-item>
            </el-form>

            <el-divider class="material-divider" />

            <h3 class="section-title">
              佐证材料
              <span v-if="task.indicator?.evidenceRequirement" class="requirement">
                （要求：{{ task.indicator.evidenceRequirement }}）
              </span>
            </h3>
            <MaterialUploader
              :key="`${editingId ?? 'new'}-${formReadonly}-${yearLocked}`"
              v-model="fileIds"
              :initial-files="initialFiles"
              :disabled="formReadonly || yearLocked"
              @update:model-value="markDirty"
            />
          </el-card>
        </el-col>

        <el-col :span="8">
          <el-card shadow="never" class="side-card">
            <h3 class="section-title">本年进度</h3>
            <div class="progress-stats">
              <div class="stat-row">
                <span class="stat-label">目标</span>
                <span class="stat-value">{{ formatTargetValue(yearItem?.target ?? null, caliber) }}</span>
              </div>
              <div class="stat-row">
                <span class="stat-label">已认定</span>
                <span class="stat-value">{{ formatTargetValue(yearItem?.actualValue ?? null, caliber) }}</span>
              </div>
              <div class="stat-row">
                <span class="stat-label">待审</span>
                <span class="stat-value">
                  {{ yearSubmissions.filter((item) => item.status === 'PENDING_REVIEW').length }} 条
                </span>
              </div>
            </div>
            <el-progress
              v-if="yearItem?.completionRate !== null && yearItem?.completionRate !== undefined"
              :percentage="progressPercentage(yearItem.completionRate)"
              :stroke-width="8"
              :color="yearItem.completionRate >= 1 ? '#67C23A' : '#2B5BC0'"
            >
              <span class="rate-text">{{ formatPercent(yearItem.completionRate) }}</span>
            </el-progress>
            <div v-else class="empty-text">完成率 —</div>
          </el-card>

          <el-card shadow="never" class="side-card">
            <h3 class="section-title">填报记录</h3>
            <SubmissionTimeline
              :submissions="yearSubmissions"
              :caliber="caliber"
              :unit-label="unitLabel"
            />
          </el-card>
        </el-col>
      </el-row>

      <div v-if="!yearNotActivated && !formReadonly && !yearLocked" class="sticky-bar">
        <el-button @click="goBack">取消</el-button>
        <el-button :loading="saving" :disabled="submitting" @click="handleSaveDraft">存草稿</el-button>
        <el-button type="primary" :loading="submitting" :disabled="saving" @click="handleSubmit">
          提交审核
        </el-button>
      </div>
    </template>

    <TaskDetailDrawer v-model="detailVisible" :task-id="taskId" @submit="reload" />
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.page-alert {
  margin-bottom: var(--space-4);
}

.info-card,
.form-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.task-info {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  flex-wrap: wrap;
  font-size: 13px;
}

.info-item {
  color: var(--el-text-color-regular);
  font-variant-numeric: tabular-nums;
}

.deadline-urgent {
  color: var(--el-color-warning);
  font-weight: 600;
}

.deadline-overdue {
  color: var(--el-color-danger);
  font-weight: 600;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 var(--space-3);
}

.requirement {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.metric-form {
  max-width: 720px;
}

.field-unit {
  margin-left: var(--space-2);
  color: var(--el-text-color-regular);
}

.field-tag {
  margin-left: var(--space-2);
}

.field-error {
  margin-left: var(--space-2);
  font-size: 12px;
  color: var(--el-color-danger);
}

.calc-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.calc-box {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
  padding: var(--space-1) var(--space-3);
  font-variant-numeric: tabular-nums;
}

.readonly-value {
  font-variant-numeric: tabular-nums;
}

.material-divider {
  margin: var(--space-4) 0;
}

.side-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.progress-stats {
  margin-bottom: var(--space-3);
}

.stat-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  line-height: 26px;
}

.stat-label {
  color: var(--el-text-color-secondary);
}

.stat-value {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.rate-text {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.empty-text {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.sticky-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  height: 56px;
  align-items: center;
  padding: 0 var(--space-5);
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-lighter);
  border-radius: var(--radius-card);
  z-index: 10;
}

/* 移动端（≤768px，UI 规范 5.2）：左右两栏改单列，表单单列、底部操作吸附 */
@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }

  .submit-page :deep(.el-col-16),
  .submit-page :deep(.el-col-8) {
    max-width: 100%;
    flex: 0 0 100%;
  }

  .metric-form {
    max-width: 100%;
  }

  .metric-form :deep(.el-form-item__label) {
    width: 110px !important;
  }

  .task-info {
    gap: var(--space-2);
    font-size: 12px;
  }

  .sticky-bar {
    padding: 0 var(--space-3);
  }

  .sticky-bar .el-button {
    flex: 1;
    margin-left: 0;
  }
}
</style>
