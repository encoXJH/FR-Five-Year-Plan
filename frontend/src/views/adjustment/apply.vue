<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdjustmentCompareTable from './components/AdjustmentCompareTable.vue'
import BackButton from '@/components/BackButton.vue'
import StatusTag from '@/components/StatusTag.vue'
import MaterialUploader from '@/views/submission/components/MaterialUploader.vue'
import { createAdjustment, previewAdjustment } from '@/api/adjustment'
import { getTask, listAllTasks } from '@/api/task'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { listPlans } from '@/api/plan'
import { getIndicator, listIndicators } from '@/api/indicator'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { ApiError } from '@/utils/request'
import { enumLabel } from '@/utils/enumLabels'
import {
  adjustmentTypeHint,
  adjustmentTypeOptions,
  checkTargetYear,
  isTransferType,
  summarizeItems,
} from '@/utils/adjustment'
import { formatTargetValue, hasQuantitativeFields, isPercentCaliber } from '@/utils/indicator'
import { canAssignNewB, percentToStore } from '@/utils/allocation'
import { canWriteTask, primaryTaskIndicator } from '@/utils/task'
import type { FieldError } from '@/types/api'
import type { PlanCard } from '@/types/plan'
import type { IndicatorTreeNode } from '@/types/indicator'
import type { TaskDetail, TaskListItem } from '@/types/task'
import type {
  AdjustmentItem,
  AdjustmentPreviewRequest,
  AdjustmentPreviewResult,
} from '@/types/adjustment'
import type { AdjustmentType, StatCaliber } from '@/types/enums'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

// ---------- 步骤 ----------
const step = ref(1)

// ---------- 计划与学院任务选择 ----------
const planOptions = ref<PlanCard[]>([])
const selectedPlanId = ref('')
const taskOptions = ref<TaskListItem[]>([])
const taskLoading = ref(false)
const task = ref<TaskDetail | null>(null)
const directionByIndicator = ref<Map<string, string>>(new Map())
let planRequestSeq = 0
let taskDetailRequestSeq = 0

const currentPlan = computed(() => planOptions.value.find((item) => item.id === selectedPlanId.value) ?? null)

const form = reactive({
  taskId: '',
  type: '' as AdjustmentType | '',
  fromYear: null as number | null,
  amount: undefined as number | undefined,
  toYear: null as number | null,
  toUnitId: '',
  reason: '',
  fileIds: [] as string[],
})

const selectedTaskOption = computed(() => taskOptions.value.find((item) => item.id === form.taskId) ?? null)
const primaryIndicator = computed(() => primaryTaskIndicator(task.value ?? selectedTaskOption.value))
const caliber = computed<StatCaliber | null>(() => primaryIndicator.value?.statCaliber ?? null)
const isBoolean = computed(() => caliber.value === 'BOOLEAN')
const isTargetChange = computed(() => form.type === 'TARGET_CHANGE')
const isHistoricalTask = computed(
  () => !isTargetChange.value && task.value?.isHistorical === true,
)
const formReadonly = computed(() => step.value === 2 || isHistoricalTask.value)

/** 类型选项按口径过滤（70006 引导；TARGET_CHANGE 仅管理员） */
const typeOptions = computed(() => adjustmentTypeOptions(caliber.value, isAdmin.value))
const selectedTypeHint = computed(() => adjustmentTypeHint(caliber.value, isAdmin.value))

function typeLabel(type: AdjustmentType | ''): string {
  return type ? enumLabel('AdjustmentType', type, userStore.enums) : '—'
}

/** 规划期年度（任务详情 plan 或年度明细推断） */
const planYears = computed<number[]>(() => {
  const detail = task.value
  if (!detail) return []
  if (detail.plan) {
    const list: number[] = []
    for (let year = detail.plan.startYear; year <= detail.plan.endYear; year += 1) {
      list.push(year)
    }
    return list
  }
  return (detail.years ?? []).map((item) => item.year).sort((a, b) => a - b)
})

const planEndYear = computed(() => planYears.value[planYears.value.length - 1] ?? 9999)

/** 原年度选项：INCREMENT 取有目标的年度；BOOLEAN 取计划完成年 */
const fromYearOptions = computed<number[]>(() => {
  const years = task.value?.years ?? []
  if (isBoolean.value) {
    return years.filter((item) => item.target === true).map((item) => item.year)
  }
  return years
    .filter((item) => typeof item.target === 'number' && item.target > 0)
    .map((item) => item.year)
})

const fromYearItem = computed(
  () => (task.value?.years ?? []).find((item) => item.year === form.fromYear) ?? null,
)

// ---------- 试算 ----------
const previewing = ref(false)
const preview = ref<AdjustmentPreviewResult | null>(null)
const previewErrors = ref<FieldError[]>([])
const previewFailed = ref('')

/** 转出方已冻结的调整额度（70004；preview / 详情返回 frozenAmount 时展示，缺省不显示） */
const frozenAmount = computed(() => {
  const value = preview.value?.frozenAmount
  return typeof value === 'number' && value > 0 ? value : null
})

/** 可调量（体验层提示：年目标 − 已认定 − 冻结量；冻结量以 preview 返回为准） */
const adjustableHint = computed(() => {
  const item = fromYearItem.value
  if (!item || typeof item.target !== 'number') return null
  const completed = typeof item.actualValue === 'number' ? item.actualValue : 0
  return item.target - completed - (frozenAmount.value ?? 0)
})

/** 目标年份选项：延期类晚于原年度；转移可同年 */
const toYearOptions = computed<number[]>(() => {
  if (form.fromYear === null) return planYears.value
  return planYears.value.filter((year) => {
    if (form.type === 'TRANSFER') return year >= (form.fromYear ?? year)
    return year > (form.fromYear ?? year)
  })
})

const unitOptions = ref<OrgUnit[]>([])

/** 接收单元选项（排除转出方，仅保留启用且允许承接新 B 的单元） */
const receiverOptions = computed(() =>
  unitOptions.value.filter((unit) => unit.id !== task.value?.unit?.id && canAssignNewB(unit)),
)

function directionOf(option: TaskListItem): string {
  const indicatorId = primaryTaskIndicator(option)?.id
  return (indicatorId ? directionByIndicator.value.get(indicatorId) : null) ?? '未分配建设方向'
}

function remainingText(option: TaskListItem): string {
  if (typeof option.adjustableAmount !== 'number') return '—'
  return formatTargetValue(option.adjustableAmount, primaryTaskIndicator(option)?.statCaliber)
}

function selectedTaskRowClass({ row }: { row: TaskListItem }): string {
  return row.id === form.taskId ? 'is-selected-task' : ''
}

async function selectTask(taskId: string, presetDetail?: TaskDetail): Promise<void> {
  const changed = form.taskId !== taskId
  form.taskId = taskId
  task.value = null
  if (changed) {
    form.type = ''
    form.fileIds = []
  }
  form.fromYear = null
  form.amount = undefined
  form.toYear = null
  form.toUnitId = ''
  preview.value = null
  previewErrors.value = []
  previewFailed.value = ''
  const requestSeq = ++taskDetailRequestSeq
  if (!taskId) return
  try {
    const detail = presetDetail ?? await getTask(taskId)
    if (requestSeq !== taskDetailRequestSeq || form.taskId !== taskId) return
    task.value = detail
    const planYear = fromYearOptions.value
    if (isBoolean.value && planYear.length === 1) {
      form.fromYear = planYear[0]
    }
    if (route.query.type === 'POSTPONE' && ['INCREMENT', 'BOOLEAN'].includes(caliber.value ?? '')) {
      const fromYear = Number(route.query.fromYear)
      const toYear = Number(route.query.toYear)
      const amount = Number(route.query.amount)
      form.type = 'POSTPONE'
      if (fromYearOptions.value.includes(fromYear)) form.fromYear = fromYear
      if (toYearOptions.value.includes(toYear) && form.fromYear !== null && toYear > form.fromYear) form.toYear = toYear
      if (isBoolean.value) form.amount = 1
      else if (Number.isFinite(amount) && amount > 0) form.amount = amount
    }
  } catch {
    // 拦截器已提示
  }
}

// ---------- TARGET_CHANGE（指标级，仅管理员） ----------
const indicatorOptions = ref<{ id: string; label: string; caliber: StatCaliber }[]>([])
const tcForm = reactive({
  planId: '',
  indicatorId: '',
  year: null as number | null,
  target: undefined as number | undefined,
  /** 比率类按 % 显示输入 */
  percentTarget: undefined as number | undefined,
})

const tcIndicator = computed(
  () => indicatorOptions.value.find((item) => item.id === tcForm.indicatorId) ?? null,
)
const tcIsPercent = computed(() =>
  tcIndicator.value ? isPercentCaliber(tcIndicator.value.caliber) : false,
)
const tcIsBoolean = computed(() => tcIndicator.value?.caliber === 'BOOLEAN')

const tcYearOptions = computed<number[]>(() => {
  const plan = planOptions.value.find((item) => item.id === tcForm.planId)
  if (!plan) return []
  const list: number[] = []
  for (let year = plan.startYear; year <= plan.endYear; year += 1) {
    list.push(year)
  }
  return list
})

const compareYears = computed(() => isTargetChange.value ? tcYearOptions.value : planYears.value)
const compareCaliber = computed<StatCaliber>(() =>
  isTargetChange.value ? (tcIndicator.value?.caliber ?? 'INCREMENT') : (caliber.value ?? 'INCREMENT'),
)

function resetPlanSelection(planId: string): void {
  selectedPlanId.value = planId
  planRequestSeq += 1
  taskDetailRequestSeq += 1
  taskOptions.value = []
  directionByIndicator.value = new Map()
  indicatorOptions.value = []
  task.value = null
  form.taskId = ''
  form.type = ''
  form.fromYear = null
  form.amount = undefined
  form.toYear = null
  form.toUnitId = ''
  form.fileIds = []
  tcForm.planId = planId
  tcForm.indicatorId = ''
  tcForm.year = null
  tcForm.target = undefined
  tcForm.percentTarget = undefined
  preview.value = null
  previewErrors.value = []
  previewFailed.value = ''
  step.value = 1
}

async function loadPlanData(planId: string, indicatorId?: string): Promise<void> {
  if (!planId) return
  const requestSeq = ++planRequestSeq
  taskLoading.value = true
  try {
    const [tasks, tree] = await Promise.all([
      listAllTasks({ planId, indicatorId: indicatorId || undefined, isHistorical: false }),
      listIndicators(planId),
    ])
    if (requestSeq !== planRequestSeq || selectedPlanId.value !== planId) return
    taskOptions.value = tasks.filter((item) => canWriteTask(item))
    const directions = new Map<string, string>()
    const collectDirections = (nodes: IndicatorTreeNode[]): void => {
      for (const node of nodes) {
        directions.set(node.id, node.directionName ?? '未分配建设方向')
        collectDirections(node.children ?? [])
      }
    }
    collectDirections(tree)
    directionByIndicator.value = directions
    indicatorOptions.value = tree
      .filter((node) => node.level === 'A' && hasQuantitativeFields(node.level, node.isQuantifiable) && node.statCaliber)
      .map((node) => ({
        id: node.id,
        label: `${node.code} ${node.name}`,
        caliber: node.statCaliber!,
      }))
  } catch {
    // 拦截器已提示；旧计划响应不会覆盖当前选择
  } finally {
    if (requestSeq === planRequestSeq) taskLoading.value = false
  }
}

async function changePlan(planId: string): Promise<void> {
  resetPlanSelection(planId)
  await loadPlanData(planId)
}

/** 组装试算 / 提交共用请求体 */
function buildRequest(): AdjustmentPreviewRequest | null {
  if (!form.type) return null
  if (isTargetChange.value) {
    const newTarget = tcIsBoolean.value
      ? undefined
      : tcIsPercent.value
        ? percentToStore(tcForm.percentTarget)
        : (tcForm.target ?? undefined)
    return {
      type: 'TARGET_CHANGE',
      indicatorId: tcForm.indicatorId,
      items: [{ year: tcForm.year ?? undefined, newTarget }],
    }
  }
  const items: AdjustmentItem[] = [
    {
      fromYear: form.fromYear ?? 0,
      toYear: form.toYear ?? undefined,
      amount: isBoolean.value ? undefined : (form.amount ?? undefined),
    },
  ]
  return {
    type: form.type,
    taskId: form.taskId,
    toUnitId: isTransferType(form.type) ? form.toUnitId : undefined,
    items,
  }
}

function validateForm(): string[] {
  const errors: string[] = []
  if (!form.type) errors.push('请选择调整类型')
  if (isTargetChange.value) {
    if (!tcForm.planId) errors.push('请选择所属计划')
    if (!tcForm.indicatorId) errors.push('请选择要调整的 指标')
    if (tcForm.year === null) errors.push('请选择目标年度')
    if (!tcIsBoolean.value && !tcIsPercent.value && tcForm.target === undefined) {
      errors.push('请填写新的目标值')
    }
    if (!tcIsBoolean.value && tcIsPercent.value && tcForm.percentTarget === undefined) {
      errors.push('请填写新的目标百分比')
    }
  } else {
    if (!form.taskId) errors.push('请选择 学院任务')
    if (form.fromYear === null) errors.push('请选择原年度')
    if (!isBoolean.value && (form.amount === undefined || form.amount <= 0)) {
      errors.push('请填写调整数量（≥ 1）')
    }
    if (form.toYear === null) {
      errors.push('请选择目标年份')
    } else if (form.fromYear !== null) {
      const yearError = checkTargetYear(form.type as AdjustmentType, form.fromYear, form.toYear, planEndYear.value)
      if (yearError) errors.push(yearError)
    }
    if (isTransferType(form.type as AdjustmentType) && !form.toUnitId) {
      errors.push('转移类调整必须选择接收单元')
    }
  }
  if (!form.reason.trim()) errors.push('请填写申请原因（≤ 500 字）')
  return errors
}

async function handlePreview(): Promise<void> {
  if (isHistoricalTask.value) {
    ElMessage.warning('历史任务只读，不能发起调整申请')
    return
  }
  const errors = validateForm()
  if (errors.length > 0) {
    ElMessage.error(errors[0])
    return
  }
  if (!isTargetChange.value && !canWriteTask(task.value)) {
    ElMessage.warning('请等待 学院任务详情加载完成')
    return
  }
  const request = buildRequest()
  if (!request) return
  previewing.value = true
  previewFailed.value = ''
  try {
    // 后端 preview 与 create 共用请求模型：reason @NotBlank 必填（决策记录十八节）
    const result = await previewAdjustment({ ...request, reason: form.reason.trim() })
    preview.value = result
    previewErrors.value = result.errors ?? []
    step.value = 2
  } catch (error) {
    if (error instanceof ApiError) {
      previewFailed.value = `${error.message}（${error.code}）`
    }
  } finally {
    previewing.value = false
  }
}

// ---------- 提交 ----------
const submitting = ref(false)

/** 幂等键（UI 规范 10.4）：成功后重新生成，失败重试沿用同一个 */
const submitKey = ref(newIdempotencyKey())

const canSubmit = computed(
  () =>
    !isHistoricalTask.value &&
    preview.value !== null &&
    preview.value.valid === true &&
    (isTargetChange.value || canWriteTask(task.value)),
)

const submitSummary = computed(() => {
  if (isTargetChange.value) {
    return `${tcIndicator.value?.label ?? ''} ${tcForm.year ?? ''} 年目标调整`
  }
  return `${task.value?.code ?? ''}：${summarizeItems(
    [
      {
        fromYear: form.fromYear ?? 0,
        toYear: form.toYear ?? undefined,
        amount: isBoolean.value ? undefined : form.amount,
      },
    ],
    primaryIndicator.value?.unitLabel,
  )}${isTransferType(form.type as AdjustmentType) ? `，接收单元：${receiverOptions.value.find((u) => u.id === form.toUnitId)?.name ?? ''}` : ''}`
})

async function handleSubmit(): Promise<void> {
  if (isHistoricalTask.value) {
    ElMessage.warning('历史任务只读，不能提交调整申请')
    return
  }
  const request = buildRequest()
  if (!request || !canSubmit.value) return
  submitting.value = true
  try {
    await createAdjustment(
      { ...request, reason: form.reason.trim(), fileIds: form.fileIds },
      submitKey.value,
    )
    submitKey.value = newIdempotencyKey()
    ElMessage.success('调整申请已提交，等待建设方向负责人初审')
    dirty.value = false
    router.push('/adjustments')
  } catch (error) {
    // 失败重试沿用同一个 Idempotency-Key
    if (error instanceof ApiError) {
      ElMessage.error(`${error.message}（${error.code}）`)
    }
  } finally {
    submitting.value = false
  }
}

// ---------- 离开确认（UI 规范 10.4：表单有修改时提示） ----------
const dirty = ref(false)
/** 初始数据（含 URL 预置任务）加载完成后才开始监听修改 */
const watchReady = ref(false)

watch(
  [() => ({ ...form }), () => ({ ...tcForm })],
  () => {
    if (watchReady.value) dirty.value = true
  },
  { deep: true },
)

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('有未保存的内容，确定离开？', '离开确认', {
      type: 'warning',
      confirmButtonText: '确定离开',
      cancelButtonText: '继续编辑',
    })
    return true
  } catch {
    return false
  }
})

function backToEdit(): void {
  step.value = 1
}

onMounted(async () => {
  const presetTaskId = typeof route.query.taskId === 'string' ? route.query.taskId : ''
  const presetIndicatorId = typeof route.query.indicatorId === 'string' ? route.query.indicatorId : ''
  const presetPlanId = typeof route.query.planId === 'string' ? route.query.planId : ''
  listOrgUnits()
    .then((list) => (unitOptions.value = list))
    .catch(() => undefined)
  try {
    const plans = await listPlans()
    planOptions.value = plans
    const presetTask = presetTaskId ? await getTask(presetTaskId) : null
    const presetIndicator = !presetTask && presetIndicatorId ? await getIndicator(presetIndicatorId) : null
    const requestedPlanId = presetTask?.planId ?? presetIndicator?.planId ?? presetPlanId
    const defaultPlan = plans.find((item) => item.id === requestedPlanId)
      ?? plans.find((item) => item.status === 'ACTIVE')
      ?? plans[0]
    const planId = defaultPlan?.id ?? ''
    resetPlanSelection(planId)
    if (presetIndicator && isAdmin.value) {
      if (presetIndicator.level === 'A') form.type = 'TARGET_CHANGE'
      else ElMessage.warning('目标调整请选择 A 类关键产出指标')
    }
    await loadPlanData(planId, !isAdmin.value ? presetIndicatorId : undefined)
    if (presetTask && presetTask.planId === planId) {
      await selectTask(presetTask.id, presetTask)
    } else if (form.type === 'TARGET_CHANGE' && presetIndicator
      && indicatorOptions.value.some((item) => item.id === presetIndicator.id)) {
      tcForm.indicatorId = presetIndicator.id
    } else if (!presetTask && presetIndicatorId && !isAdmin.value && taskOptions.value.length === 1) {
      await selectTask(taskOptions.value[0].id)
    }
  } catch {
    // 深链上下文无效时保留空白表单，由拦截器提示具体权限或不存在原因
  } finally {
    watchReady.value = true
  }
})
</script>

<template>
  <div>
    <BackButton />
    <div class="page-header">
      <div>
        <h1 class="page-title">发起调整申请</h1>
        <p class="page-subtitle">延期 / 转移 / 目标调整；审批通过后生效，全程留痕</p>
      </div>
    </div>

    <el-steps :active="step - 1" align-center class="apply-steps" finish-status="success">
      <el-step title="填写申请信息" />
      <el-step title="试算对比与提交" />
    </el-steps>

    <el-card shadow="never" class="plan-switch-card">
      <div class="plan-switch-row">
        <div class="plan-switch-label">
          <strong>当前计划</strong>
          <span>切换计划后重新选择任务</span>
        </div>
        <el-select
          v-model="selectedPlanId"
          filterable
          placeholder="请选择计划"
          class="plan-select"
          @change="changePlan"
        >
          <el-option
            v-for="plan in planOptions"
            :key="plan.id"
            :label="`${plan.name}（${plan.startYear}–${plan.endYear}）`"
            :value="plan.id"
          >
            <div class="plan-option">
              <span>{{ plan.name }}</span>
              <span class="plan-option-years">{{ plan.startYear }}–{{ plan.endYear }}</span>
            </div>
          </el-option>
        </el-select>
        <template v-if="currentPlan">
          <StatusTag kind="PlanStatus" :value="currentPlan.status" />
          <span class="current-plan-years">{{ currentPlan.startYear }}–{{ currentPlan.endYear }}</span>
        </template>
      </div>
    </el-card>

    <el-card v-if="!isTargetChange" shadow="never" class="task-select-card">
      <template #header>
        <div class="task-card-header">
          <span class="card-title">勾选需要调整的学院任务</span>
          <span class="field-tip-inline">每次申请选择 1 个任务</span>
        </div>
      </template>
      <el-table
        v-loading="taskLoading"
        :data="taskOptions"
        border
        size="small"
        max-height="420"
        :row-class-name="selectedTaskRowClass"
        @row-click="step === 1 && selectTask($event.id)"
      >
        <el-table-column width="52" align="center" fixed="left">
          <template #default="{ row }">
            <el-radio
              :model-value="form.taskId"
              :value="row.id"
              :disabled="step === 2"
              :aria-label="`选择任务 ${row.code}`"
              @change="selectTask(row.id)"
            />
          </template>
        </el-table-column>
        <el-table-column label="学院" min-width="165">
          <template #default="{ row }">
            <div class="primary-cell"><span class="mono">{{ row.unit?.code ?? '—' }}</span> {{ row.unit?.name ?? '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="建设方向" min-width="150">
          <template #default="{ row }">{{ directionOf(row) }}</template>
        </el-table-column>
        <el-table-column label="关键产出指标" min-width="230">
          <template #default="{ row }">
            <div class="primary-cell"><span class="mono">{{ primaryTaskIndicator(row)?.code ?? '—' }}</span></div>
            <div class="secondary-cell">{{ primaryTaskIndicator(row)?.name ?? '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="任务编号" min-width="145">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="当前年度目标" width="145" align="right">
          <template #default="{ row }">
            <div>{{ row.displayYear ? `${row.displayYear} 年` : '—' }}</div>
            <div class="secondary-cell">
              {{ formatTargetValue(row.yearTarget, primaryTaskIndicator(row)?.statCaliber) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="剩余可调整量" width="150" align="right">
          <template #default="{ row }">
            <span :class="{ 'remaining-value': typeof row.adjustableAmount === 'number' }">
              {{ remainingText(row) }}
            </span>
            <span v-if="typeof row.adjustableAmount === 'number' && primaryTaskIndicator(row)?.unitLabel">
              {{ primaryTaskIndicator(row)?.unitLabel }}
            </span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="70" description="当前计划暂无可调整的学院任务" />
        </template>
      </el-table>
      <div class="task-table-tip">剩余可调整量 = 当前年度目标 − 已认定 − 已冻结；其他统计口径显示“—”</div>
      <el-alert
        v-if="form.taskId"
        :title="selectedTypeHint"
        type="info"
        :closable="false"
        class="selection-type-hint"
      />
    </el-card>

    <div class="apply-layout">
      <!-- 申请表单 -->
      <el-card shadow="never" class="form-card">
        <el-alert
          v-if="isHistoricalTask"
          type="info"
          :closable="false"
          title="历史任务只读，不能发起调整、试算或上传附件"
          class="form-alert"
        />
        <el-form label-width="110px" label-position="left" @submit.prevent>
          <el-form-item label="调整类型" required>
            <el-radio-group v-model="form.type" :disabled="formReadonly">
              <el-radio
                v-for="option in typeOptions"
                :key="option.type"
                :value="option.type"
                :disabled="!option.enabled"
                :title="option.reason ?? undefined"
              >
                {{ typeLabel(option.type) }}
              </el-radio>
            </el-radio-group>
            <div v-if="!isAdmin && caliber && caliber !== 'INCREMENT' && caliber !== 'BOOLEAN'" class="field-tip">
              该口径不支持延期 / 转移，如需变更请联系管理员发起目标调整（70006）
            </div>
          </el-form-item>

          <!-- 任务级调整（延期 / 转移 / 延期+转移） -->
          <template v-if="form.type && !isTargetChange">
            <el-form-item label="原年度" required>
              <el-select
                v-model="form.fromYear"
                placeholder="选择原年度"
                style="width: 160px"
                :disabled="formReadonly"
                @change="form.toYear = null; preview = null"
              >
                <el-option
                  v-for="year in fromYearOptions"
                  :key="year"
                  :label="`${year} 年`"
                  :value="year"
                />
              </el-select>
              <div v-if="fromYearItem" class="field-tip">
                本年目标 {{ formatTargetValue(fromYearItem.target, caliber ?? 'INCREMENT') }}
                · 已认定 {{ formatTargetValue(fromYearItem.actualValue, caliber ?? 'INCREMENT') }}
                <template v-if="frozenAmount !== null">
                  · <span class="frozen-text">🔒 冻结 {{ frozenAmount }}</span>
                </template>
                <template v-if="adjustableHint !== null"> · 可调约 {{ adjustableHint }}</template>
              </div>
              <div v-else-if="isBoolean && fromYearOptions.length === 0" class="field-tip">
                该任务未设置计划完成年，无法延期
              </div>
            </el-form-item>

            <el-form-item v-if="!isBoolean" label="调整数量" required>
              <el-input-number
                v-model="form.amount"
                :min="1"
                :precision="0"
                controls-position="right"
                :disabled="formReadonly"
              />
              <span v-if="primaryIndicator?.unitLabel" class="field-unit">
                {{ primaryIndicator.unitLabel }}
              </span>
              <span class="field-tip-inline">不超过可调量（70001）</span>
            </el-form-item>

            <el-form-item label="目标年份" required>
              <el-select
                v-model="form.toYear"
                placeholder="选择目标年份"
                style="width: 160px"
                :disabled="formReadonly"
              >
                <el-option
                  v-for="year in toYearOptions"
                  :key="year"
                  :label="`${year} 年`"
                  :value="year"
                />
              </el-select>
              <span class="field-tip-inline">最晚 {{ planEndYear }} 年（70003）</span>
            </el-form-item>

            <el-form-item v-if="isTransferType(form.type)" label="接收单元" required>
              <el-select
                v-model="form.toUnitId"
                filterable
                placeholder="选择接收学院"
                style="width: 100%"
                :disabled="formReadonly"
              >
                <el-option
                  v-for="unit in receiverOptions"
                  :key="unit.id"
                  :label="`${unit.code} ${unit.name}`"
                  :value="unit.id"
                />
              </el-select>
              <div class="field-tip">接收方原本没有该指标的学院任务时，生效后系统自动新建</div>
            </el-form-item>
          </template>

          <!-- TARGET_CHANGE（指标级，仅管理员） -->
          <template v-else-if="isTargetChange">
            <el-form-item label=" ">
              <el-button link type="primary" :disabled="step === 2" @click="form.type = ''">
                返回学院任务调整
              </el-button>
            </el-form-item>
            <el-form-item label="调整指标" required>
              <el-select
                v-model="tcForm.indicatorId"
                filterable
                placeholder="选择 指标"
                style="width: 100%"
                :disabled="step === 2 || !tcForm.planId"
                @change="preview = null"
              >
                <el-option
                  v-for="option in indicatorOptions"
                  :key="option.id"
                  :label="option.label"
                  :value="option.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="目标年度" required>
              <el-select
                v-model="tcForm.year"
                placeholder="选择年度"
                style="width: 160px"
                :disabled="formReadonly"
              >
                <el-option
                  v-for="year in tcYearOptions"
                  :key="year"
                  :label="`${year} 年`"
                  :value="year"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-if="tcIsBoolean" label="新目标值">
              <span class="field-tip-inline">是否型指标请使用「延期」调整计划完成年份</span>
            </el-form-item>
            <el-form-item v-else-if="tcIsPercent" label="新目标值" required>
              <el-input-number
                v-model="tcForm.percentTarget"
                :min="0"
                :precision="1"
                controls-position="right"
                :disabled="formReadonly"
              />
              <span class="field-unit">%</span>
            </el-form-item>
            <el-form-item v-else label="新目标值" required>
              <el-input-number
                v-model="tcForm.target"
                :min="0"
                :precision="4"
                controls-position="right"
                :disabled="formReadonly"
              />
            </el-form-item>
          </template>

          <el-form-item label="申请原因" required>
            <el-input
              v-model="form.reason"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="必填，说明调整背景与依据（≤ 500 字）"
              :disabled="formReadonly"
            />
          </el-form-item>

          <el-form-item label="附件">
            <MaterialUploader
              :key="`${selectedPlanId}:${form.taskId}`"
              v-model="form.fileIds"
              biz-type="ADJUSTMENT_ATTACHMENT"
              :disabled="formReadonly"
            />
          </el-form-item>

          <el-alert
            v-if="previewFailed"
            type="error"
            :closable="false"
            :title="previewFailed"
            class="form-alert"
          />

          <el-form-item label=" ">
            <el-button v-if="step === 2" @click="backToEdit">返回修改</el-button>
            <el-button
              v-if="!isHistoricalTask"
              type="primary"
              :loading="previewing"
              @click="handlePreview"
            >
              试算对比
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 试算对比 -->
      <el-card shadow="never" class="preview-card">
        <template #header>
          <span class="card-title">调整前 / 后对比</span>
        </template>

        <template v-if="step === 2 && preview">
          <el-alert
            v-if="previewErrors.length > 0"
            type="error"
            :closable="false"
            title="校验未通过，请修改后重新试算"
            class="preview-alert"
          >
            <ul class="error-list">
              <li v-for="(error, index) in previewErrors" :key="index">
                {{ error.field ? `${error.field}：` : '' }}{{ error.message }}
              </li>
            </ul>
          </el-alert>
          <el-alert
            v-else
            type="success"
            :closable="false"
            title="试算通过，可提交申请"
            class="preview-alert"
          />

          <AdjustmentCompareTable
            :snapshot="preview.snapshot"
            :years="compareYears"
            :caliber="compareCaliber"
            :type="form.type || undefined"
            caption="试算结果（未生效）"
          />

          <div v-if="frozenAmount !== null" class="frozen-hint">
            🔒 冻结 {{ frozenAmount }}：提交后转出方该额度将被冻结，审批期间不可再次申请（70004），审批结束后解除
          </div>

          <div class="submit-bar">
            <div class="submit-summary">
              {{ typeLabel(form.type) }} · {{ submitSummary }}
            </div>
            <el-button
              type="primary"
              size="large"
              :disabled="!canSubmit"
              :loading="submitting"
              @click="handleSubmit"
            >
              确认提交申请
            </el-button>
          </div>
          <div class="field-tip">
            审批链：建设方向负责人初审 → {{ isTransferType(form.type as AdjustmentType) ? '接收方确认 → ' : '' }}校级负责人审批；审批期间调整额度将被冻结（70004）
          </div>
        </template>

        <el-empty
          v-else
          :image-size="120"
          description="填写左侧表单后点击「试算对比」，查看调整前后差异与守恒校验"
        />
      </el-card>
    </div>
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

.apply-steps {
  max-width: 480px;
  margin: 0 auto var(--space-4);
}

.apply-layout {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
  gap: var(--space-4);
  align-items: start;
}

.plan-switch-card,
.task-select-card,
.form-card,
.preview-card {
  border-radius: var(--radius-card);
}

.plan-switch-card,
.task-select-card {
  margin-bottom: var(--space-4);
}

.plan-switch-row,
.plan-option,
.task-card-header {
  display: flex;
  align-items: center;
}

.plan-switch-row {
  gap: var(--space-3);
  flex-wrap: wrap;
}

.plan-switch-label {
  display: flex;
  flex-direction: column;
  min-width: 150px;
  gap: 2px;
}

.plan-switch-label span,
.current-plan-years,
.plan-option-years,
.task-table-tip,
.secondary-cell {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.plan-select {
  flex: 1;
  min-width: 260px;
  max-width: 620px;
}

.plan-option,
.task-card-header {
  justify-content: space-between;
  gap: var(--space-3);
}

.task-card-header .field-tip-inline {
  margin-left: 0;
}

.mono {
  font-family: var(--app-font-mono);
}

.primary-cell {
  color: var(--el-text-color-primary);
  line-height: 20px;
}

.secondary-cell {
  line-height: 18px;
}

.remaining-value {
  color: var(--el-color-primary);
  font-weight: 600;
  margin-right: 2px;
}

.task-table-tip {
  margin-top: var(--space-2);
}

.selection-type-hint {
  margin-top: var(--space-3);
}

:deep(.task-select-card .el-radio) {
  margin-right: 0;
}

:deep(.task-select-card .el-radio__label) {
  display: none;
}

:deep(.task-select-card .is-selected-task > td.el-table__cell) {
  background: var(--el-color-primary-light-9);
}

:deep(.task-select-card .el-table__row) {
  cursor: pointer;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
}

.field-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 20px;
  width: 100%;
}

.field-tip-inline {
  margin-left: var(--space-2);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.field-unit {
  margin-left: var(--space-2);
  color: var(--el-text-color-regular);
}

.frozen-text {
  color: var(--el-color-warning);
  font-weight: 600;
}

.frozen-hint {
  margin-top: var(--space-3);
  font-size: 12px;
  color: var(--el-color-warning);
}

.form-alert {
  margin-bottom: var(--space-3);
}

.preview-alert {
  margin-bottom: var(--space-3);
}

.error-list {
  margin: var(--space-1) 0 0;
  padding-left: var(--space-4);
}

.submit-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: var(--space-4);
  flex-wrap: wrap;
}

.submit-summary {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

@media (max-width: 768px) {
  .apply-layout {
    grid-template-columns: 1fr;
  }

  .plan-switch-label {
    min-width: 100%;
  }

  .plan-select {
    min-width: 100%;
    max-width: none;
  }

  .task-card-header {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-1);
  }
}
</style>
