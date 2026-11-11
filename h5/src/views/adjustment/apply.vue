<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import PageState from '@/components/PageState.vue'
import PlanSwitcher from '@/components/PlanSwitcher.vue'
import FileUploader from '@/components/FileUploader.vue'
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
  buildConservationLines,
  checkTargetYear,
  diffCell,
  groupSnapshot,
  groupTotal,
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

const step = ref(1)
const planOptions = ref<PlanCard[]>([])
const selectedPlanId = ref('')
const taskOptions = ref<TaskListItem[]>([])
const taskLoading = ref(false)
const task = ref<TaskDetail | null>(null)
const indicatorOptions = ref<{ id: string; label: string; caliber: StatCaliber }[]>([])
const unitOptions = ref<OrgUnit[]>([])
const directionByIndicator = ref<Map<string, string>>(new Map())
let planRequestSeq = 0
let taskDetailRequestSeq = 0

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
const tcForm = reactive({
  indicatorId: '',
  year: null as number | null,
  target: undefined as number | undefined,
  percentTarget: undefined as number | undefined,
})

const selectedTaskOption = computed(() => taskOptions.value.find((item) => item.id === form.taskId) ?? null)
const primaryIndicator = computed(() => primaryTaskIndicator(task.value ?? selectedTaskOption.value))
const caliber = computed<StatCaliber | null>(() => primaryIndicator.value?.statCaliber ?? null)
const isBoolean = computed(() => caliber.value === 'BOOLEAN')
const isTargetChange = computed(() => form.type === 'TARGET_CHANGE')
const isHistoricalTask = computed(() => !isTargetChange.value && task.value?.isHistorical === true)
const formReadonly = computed(() => step.value === 2 || isHistoricalTask.value)

const typeOptions = computed(() => adjustmentTypeOptions(caliber.value, isAdmin.value))
const selectedTypeHint = computed(() => adjustmentTypeHint(caliber.value, isAdmin.value))
function typeLabel(type: AdjustmentType | ''): string {
  return type ? enumLabel('AdjustmentType', type, userStore.enums) : '—'
}

const planYears = computed<number[]>(() => {
  const detail = task.value
  if (!detail) return []
  if (detail.plan) {
    const list: number[] = []
    for (let year = detail.plan.startYear; year <= detail.plan.endYear; year += 1) list.push(year)
    return list
  }
  return (detail.years ?? []).map((item) => item.year).sort((a, b) => a - b)
})
const planEndYear = computed(() => planYears.value[planYears.value.length - 1] ?? 9999)
const fromYearOptions = computed<number[]>(() => {
  const years = task.value?.years ?? []
  if (isBoolean.value) return years.filter((item) => item.target === true).map((item) => item.year)
  return years.filter((item) => typeof item.target === 'number' && item.target > 0).map((item) => item.year)
})
const fromYearItem = computed(() => (task.value?.years ?? []).find((item) => item.year === form.fromYear) ?? null)
const toYearOptions = computed<number[]>(() => {
  if (form.fromYear === null) return planYears.value
  return planYears.value.filter((year) => {
    if (form.type === 'TRANSFER') return year >= (form.fromYear ?? year)
    return year > (form.fromYear ?? year)
  })
})
const receiverOptions = computed(() =>
  unitOptions.value.filter((unit) => unit.id !== task.value?.unit?.id && canAssignNewB(unit)),
)

const tcIndicator = computed(() => indicatorOptions.value.find((item) => item.id === tcForm.indicatorId) ?? null)
const tcIsPercent = computed(() => (tcIndicator.value ? isPercentCaliber(tcIndicator.value.caliber) : false))
const tcIsBoolean = computed(() => tcIndicator.value?.caliber === 'BOOLEAN')
const tcYearOptions = computed(() => {
  const plan = planOptions.value.find((item) => item.id === selectedPlanId.value)
  if (!plan) return []
  const list: number[] = []
  for (let year = plan.startYear; year <= plan.endYear; year += 1) list.push(year)
  return list
})
const compareYears = computed(() => (isTargetChange.value ? tcYearOptions.value : planYears.value))
const compareCaliber = computed<StatCaliber>(() =>
  isTargetChange.value ? (tcIndicator.value?.caliber ?? 'INCREMENT') : (caliber.value ?? 'INCREMENT'),
)

const previewing = ref(false)
const preview = ref<AdjustmentPreviewResult | null>(null)
const previewErrors = ref<FieldError[]>([])
const previewFailed = ref('')
const frozenAmount = computed(() => {
  const value = preview.value?.frozenAmount
  return typeof value === 'number' && value > 0 ? value : null
})
const adjustableHint = computed(() => {
  const item = fromYearItem.value
  if (!item || typeof item.target !== 'number') return null
  const completed = typeof item.actualValue === 'number' ? item.actualValue : 0
  return Math.round((item.target - completed - (frozenAmount.value ?? 0)) * 10000) / 10000
})

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
  tcForm.indicatorId = ''
  tcForm.year = null
  tcForm.target = undefined
  tcForm.percentTarget = undefined
  preview.value = null
  previewErrors.value = []
  previewFailed.value = ''
  step.value = 1
}

async function loadPlanData(planId: string): Promise<void> {
  if (!planId) return
  const requestSeq = ++planRequestSeq
  taskLoading.value = true
  try {
    const [tasks, tree] = await Promise.all([
      listAllTasks({ planId, isHistorical: false }),
      listIndicators(planId),
    ])
    if (requestSeq !== planRequestSeq || selectedPlanId.value !== planId) return
    taskOptions.value = tasks.filter((item) => canWriteTask(item))
    const directions = new Map<string, string>()
    const collect = (nodes: IndicatorTreeNode[]): void => {
      for (const node of nodes) {
        directions.set(node.id, node.directionName ?? '未分配建设方向')
        collect(node.children ?? [])
      }
    }
    collect(tree)
    directionByIndicator.value = directions
    indicatorOptions.value = tree
      .filter((node) => node.level === 'A' && hasQuantitativeFields(node.level, node.isQuantifiable) && node.statCaliber)
      .map((node) => ({ id: node.id, label: `${node.code} ${node.name}`, caliber: node.statCaliber! }))
  } finally {
    if (requestSeq === planRequestSeq) taskLoading.value = false
  }
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
    const booleanYears = fromYearOptions.value
    if (isBoolean.value && booleanYears.length === 1) form.fromYear = booleanYears[0]
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
  const items: AdjustmentItem[] = [{
    fromYear: form.fromYear ?? 0,
    toYear: form.toYear ?? undefined,
    amount: isBoolean.value ? undefined : (form.amount ?? undefined),
  }]
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
    if (!tcForm.indicatorId) errors.push('请选择要调整的指标')
    if (tcForm.year === null) errors.push('请选择目标年度')
    if (!tcIsBoolean.value && !tcIsPercent.value && tcForm.target === undefined) errors.push('请填写新的目标值')
    if (!tcIsBoolean.value && tcIsPercent.value && tcForm.percentTarget === undefined) errors.push('请填写新的目标百分比')
  } else {
    if (!form.taskId) errors.push('请选择学院任务')
    if (form.fromYear === null) errors.push('请选择原年度')
    if (!isBoolean.value && (form.amount === undefined || form.amount <= 0)) errors.push('请填写调整数量（≥ 1）')
    if (form.toYear === null) errors.push('请选择目标年份')
    else if (form.fromYear !== null) {
      const error = checkTargetYear(form.type as AdjustmentType, form.fromYear, form.toYear, planEndYear.value)
      if (error) errors.push(error)
    }
    if (isTransferType(form.type as AdjustmentType) && !form.toUnitId) errors.push('转移类调整必须选择接收单元')
  }
  if (!form.reason.trim()) errors.push('请填写申请原因（≤ 500 字）')
  return errors
}

async function handlePreview(): Promise<void> {
  if (isHistoricalTask.value) {
    showToast('历史任务只读，不能发起调整申请')
    return
  }
  const errors = validateForm()
  if (errors.length > 0) {
    showToast(errors[0])
    return
  }
  if (!isTargetChange.value && !canWriteTask(task.value)) {
    showToast('请等待学院任务详情加载完成')
    return
  }
  const request = buildRequest()
  if (!request) return
  previewing.value = true
  previewFailed.value = ''
  try {
    const result = await previewAdjustment({ ...request, reason: form.reason.trim() })
    preview.value = result
    previewErrors.value = result.errors ?? []
    step.value = 2
  } catch (error) {
    if (error instanceof ApiError) previewFailed.value = `${error.message}（${error.code}）`
  } finally {
    previewing.value = false
  }
}

// 快照对比
const snapshotGroups = computed(() =>
  preview.value?.snapshot ? groupSnapshot(preview.value.snapshot.before, preview.value.snapshot.after) : [],
)
const conservationLines = computed(() =>
  buildConservationLines(snapshotGroups.value, form.type || undefined),
)
function cellDiffOf(group: (typeof snapshotGroups.value)[number], year: number) {
  return diffCell(group.before[year] ?? null, group.after[year] ?? null)
}
function groupTotalOf(group: (typeof snapshotGroups.value)[number], phase: 'before' | 'after'): number | null {
  return phase === 'before'
    ? (group.totalBefore ?? groupTotal(group.before))
    : (group.totalAfter ?? groupTotal(group.after))
}

const submitting = ref(false)
const submitKey = ref(newIdempotencyKey())
const canSubmit = computed(() =>
  !isHistoricalTask.value
  && preview.value !== null
  && preview.value.valid === true
  && (isTargetChange.value || canWriteTask(task.value)),
)
const submitSummary = computed(() => {
  if (isTargetChange.value) return `${tcIndicator.value?.label ?? ''} ${tcForm.year ?? ''} 年目标调整`
  return `${task.value?.code ?? ''}：${summarizeItems(
    [{ fromYear: form.fromYear ?? 0, toYear: form.toYear ?? undefined, amount: isBoolean.value ? undefined : form.amount }],
    primaryIndicator.value?.unitLabel,
  )}${isTransferType(form.type as AdjustmentType) ? `，接收单元：${receiverOptions.value.find((u) => u.id === form.toUnitId)?.name ?? ''}` : ''}`
})

async function handleSubmit(): Promise<void> {
  if (isHistoricalTask.value) {
    showToast('历史任务只读，不能提交调整申请')
    return
  }
  const request = buildRequest()
  if (!request || !canSubmit.value) return
  submitting.value = true
  try {
    await createAdjustment({ ...request, reason: form.reason.trim(), fileIds: form.fileIds }, submitKey.value)
    submitKey.value = newIdempotencyKey()
    showToast('调整申请已提交，等待建设方向负责人初审')
    dirty.value = false
    router.push('/adjustments')
  } catch (error) {
    if (error instanceof ApiError) showToast(`${error.message}（${error.code}）`)
  } finally {
    submitting.value = false
  }
}

// 选择器
const taskPickerVisible = ref(false)
const typePickerVisible = ref(false)
const fromYearVisible = ref(false)
const toYearVisible = ref(false)
const unitPickerVisible = ref(false)
const indicatorPickerVisible = ref(false)
const tcYearVisible = ref(false)
const taskKeyword = ref('')
const filteredTasks = computed(() => {
  const kw = taskKeyword.value.trim().toLowerCase()
  if (!kw) return taskOptions.value
  return taskOptions.value.filter((item) =>
    `${item.code} ${primaryTaskIndicator(item)?.name ?? ''} ${item.unit?.name ?? ''}`.toLowerCase().includes(kw))
})
const taskLabel = computed(() =>
  selectedTaskOption.value
    ? `${selectedTaskOption.value.code} · ${primaryTaskIndicator(selectedTaskOption.value)?.name ?? ''}`
    : '')
const receiverLabel = computed(
  () => receiverOptions.value.find((unit) => unit.id === form.toUnitId)?.name ?? '',
)
const tcIndicatorLabel = computed(() => tcIndicator.value?.label ?? '')
const typeLabelText = computed(() => typeLabel(form.type))

function setType(type: AdjustmentType): void {
  form.type = type
  typePickerVisible.value = false
  form.toYear = null
  form.toUnitId = ''
  preview.value = null
}

// 离开确认
const dirty = ref(false)
watch(
  [() => ({ ...form }), () => ({ ...tcForm })],
  () => {
    if (watchReady.value) dirty.value = true
  },
  { deep: true },
)
const watchReady = ref(false)
onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await showConfirmDialog({
      title: '离开确认',
      message: '有未保存的内容，确定离开？',
      confirmButtonText: '确定离开',
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
  listOrgUnits().then((list) => {
    unitOptions.value = list
  }).catch(() => undefined)
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
    if (presetIndicator && isAdmin.value && presetIndicator.level === 'A') form.type = 'TARGET_CHANGE'
    await loadPlanData(planId)
    if (presetTask && presetTask.planId === planId) {
      await selectTask(presetTask.id, presetTask)
    } else if (form.type === 'TARGET_CHANGE' && presetIndicator
      && indicatorOptions.value.some((item) => item.id === presetIndicator.id)) {
      tcForm.indicatorId = presetIndicator.id
      step.value = 1
    } else if (!presetTask && presetIndicatorId && !isAdmin.value && taskOptions.value.length === 1) {
      await selectTask(taskOptions.value[0].id)
    }
  } catch {
    // 深链无效时保留空白表单
  } finally {
    watchReady.value = true
  }
})

function changePlan(plan: { id: string }): void {
  resetPlanSelection(plan.id)
  void loadPlanData(plan.id)
}
</script>

<template>
  <div class="page page--footer">
    <div class="page-head">
      <h1>发起调整申请</h1>
      <p class="muted small">延期 / 转移 / 目标调整；审批通过后生效，全程留痕</p>
    </div>

    <van-steps :active="step - 1" active-color="#2B5BC0">
      <van-step>填写申请信息</van-step>
      <van-step>试算对比与提交</van-step>
    </van-steps>

    <PlanSwitcher
      v-if="planOptions.length > 0 && step === 1"
      :model-value="selectedPlanId"
      :plans="planOptions"
      @change="changePlan"
    />

    <template v-if="step === 1">
      <div v-if="form.type === 'TARGET_CHANGE'" class="card">
        <div class="field">
          <label>调整指标<span class="required">*</span></label>
          <van-field readonly is-link :model-value="tcIndicatorLabel" placeholder="请选择关键产出指标" @click="indicatorPickerVisible = true" />
        </div>
        <div class="field">
          <label>目标年度<span class="required">*</span></label>
          <van-field readonly is-link :model-value="tcForm.year ? `${tcForm.year} 年` : ''" placeholder="请选择年度" @click="tcYearVisible = true" />
        </div>
        <div v-if="!tcIsBoolean" class="field">
          <label>{{ tcIsPercent ? '新目标百分比（%）' : '新目标值' }}<span class="required">*</span></label>
          <van-field
            v-if="tcIsPercent"
            v-model.number="tcForm.percentTarget"
            type="number"
            inputmode="decimal"
            placeholder="如 15 表示 15%"
          />
          <van-field v-else v-model.number="tcForm.target" type="number" inputmode="decimal" placeholder="请输入新目标值" />
        </div>
        <p v-if="tcIsBoolean" class="small muted">是否型目标调整由管理员按年度目标处理，试算结果将以服务端为准。</p>
      </div>

      <template v-else>
        <div class="card">
          <div class="field">
            <label>学院任务<span class="required">*</span></label>
            <van-field readonly is-link :model-value="taskLabel" placeholder="请选择学院任务" @click="taskPickerVisible = true" />
          </div>
          <div v-if="taskLoading" class="skeleton-block">加载任务与指标…</div>
          <template v-else-if="task">
            <div class="task-summary">
              <div class="row--between">
                <strong>{{ task.code }}</strong>
                <StatusTag kind="StatCaliber" :value="caliber ?? 'INCREMENT'" />
              </div>
              <div class="small ellipsis">{{ primaryIndicator?.name ?? '' }}</div>
              <div class="small muted">
                {{ task.unit?.name ?? '—' }} · {{ enumLabel('StatCaliber', caliber ?? 'INCREMENT', userStore.enums) }}
                <template v-if="primaryIndicator?.unitLabel"> · {{ primaryIndicator.unitLabel }}</template>
              </div>
            </div>

            <div class="field">
              <label>调整类型<span class="required">*</span></label>
              <van-field readonly is-link :model-value="typeLabelText" placeholder="请选择调整类型" @click="typePickerVisible = true" />
              <p class="small muted">{{ selectedTypeHint }}</p>
            </div>

            <div v-if="form.type" class="field">
              <label>原年度<span class="required">*</span></label>
              <van-field readonly is-link :model-value="form.fromYear ? `${form.fromYear} 年` : ''" placeholder="请选择原年度" @click="fromYearVisible = true" />
              <p v-if="adjustableHint !== null" class="small muted">
                调整量上限约 {{ adjustableHint }}{{ primaryIndicator?.unitLabel ?? '' }}（以服务端校验为准，70001 / 70004）
              </p>
            </div>

            <div v-if="form.type && !isBoolean" class="field">
              <label>调整数量<span class="required">*</span></label>
              <van-field v-model.number="form.amount" type="number" inputmode="decimal" placeholder="填写调整数量" />
            </div>

            <div v-if="form.type" class="field">
              <label>目标年份<span class="required">*</span></label>
              <van-field readonly is-link :model-value="form.toYear ? `${form.toYear} 年` : ''" placeholder="请选择目标年份" @click="toYearVisible = true" />
            </div>

            <div v-if="form.type && isTransferType(form.type)" class="field">
              <label>接收单元<span class="required">*</span></label>
              <van-field readonly is-link :model-value="receiverLabel" placeholder="请选择接收学院" @click="unitPickerVisible = true" />
            </div>
          </template>
        </div>
      </template>

      <div class="card">
        <div class="field">
          <label>申请原因<span class="required">*</span></label>
          <van-field v-model="form.reason" type="textarea" rows="3" maxlength="2000" show-word-limit placeholder="说明调整原因，将记录到审计日志" />
        </div>
        <div class="section-title">附件</div>
        <FileUploader v-model="form.fileIds" biz-type="ADJUSTMENT_ATTACHMENT" hint="可上传审批依据等附件" />
      </div>

      <div v-if="previewFailed" class="error-box">{{ previewFailed }}</div>
    </template>

    <template v-else>
      <div class="card">
        <div class="card-title">
          <span>试算结果</span>
          <span class="chip" :class="preview?.valid ? 'chip--success' : 'chip--danger'">
            {{ preview?.valid ? '校验通过' : '存在校验错误' }}
          </span>
        </div>
        <div class="kv-list">
          <div class="kv"><span class="kv__label">调整类型</span><span class="kv__value">{{ typeLabelText }}</span></div>
          <div class="kv"><span class="kv__label">调整内容</span><span class="kv__value">{{ submitSummary }}</span></div>
          <div v-if="frozenAmount !== null" class="kv"><span class="kv__label">已冻结额度</span><span class="kv__value">{{ frozenAmount }}</span></div>
          <div class="kv"><span class="kv__label">接收方已有任务</span><span class="kv__value">{{ preview?.toTaskExists === true ? '是（沿用现有任务）' : preview?.toTaskExists === false ? '否（生效时自动新建）' : '—' }}</span></div>
        </div>
        <div v-if="previewErrors.length > 0" class="error-box">
          <div v-for="(error, index) in previewErrors" :key="index">{{ error.message }}</div>
        </div>

        <div v-if="snapshotGroups.length > 0" class="compare-block">
          <div class="section-title">调整前后对比</div>
          <div v-for="group in snapshotGroups" :key="group.taskCode" class="compare-group">
            <div class="row">
              <strong class="code">{{ group.taskCode }}</strong>
              <span v-if="group.isNew" class="chip chip--success">新建</span>
            </div>
            <table class="data-table">
              <thead>
                <tr>
                  <th>阶段</th>
                  <th v-for="year in compareYears" :key="year">{{ year }}</th>
                  <th>五年</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>前</td>
                  <td v-for="year in compareYears" :key="year">{{ formatTargetValue(group.before[year] ?? null, compareCaliber) }}</td>
                  <td>{{ groupTotalOf(group, 'before') ?? '—' }}</td>
                </tr>
                <tr>
                  <td>后</td>
                  <td v-for="year in compareYears" :key="year" :class="cellDiffOf(group, year).changed ? 'cell-changed' : ''">
                    {{ formatTargetValue(group.after[year] ?? null, compareCaliber) }}
                    <span v-if="cellDiffOf(group, year).increased" class="text-success">↑</span>
                    <span v-else-if="cellDiffOf(group, year).decreased" class="text-danger">↓</span>
                  </td>
                  <td>{{ groupTotalOf(group, 'after') ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="conservationLines.length > 0" class="conservation">
            <div
              v-for="line in conservationLines"
              :key="line.taskCode"
              class="small"
              :class="line.balanced ? 'text-success' : 'text-danger'"
            >
              {{ line.taskCode }}<template v-if="line.combined">（转出 + 接收合计）</template>
              五年 {{ line.before ?? '—' }} → {{ line.after ?? '—' }}
              {{ line.balanced ? '✓ 守恒' : '✗ 不守恒（70002）' }}
            </div>
          </div>
        </div>
      </div>
    </template>

    <div class="sticky-footer">
      <div class="sticky-footer__row">
        <template v-if="step === 1">
          <van-button @click="router.push('/adjustments')">取消</van-button>
          <van-button type="primary" :loading="previewing" @click="handlePreview">试算并预览</van-button>
        </template>
        <template v-else>
          <van-button @click="backToEdit">返回修改</van-button>
          <van-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="handleSubmit">提交申请</van-button>
        </template>
      </div>
    </div>

    <!-- 选择器 -->
    <van-popup v-model:show="taskPickerVisible" position="bottom" round :style="{ height: '78vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择学院任务</strong>
          <button type="button" class="close-btn" @click="taskPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <van-search v-model="taskKeyword" placeholder="搜索任务编号 / 指标 / 学院" shape="round" />
        <div class="picker-list">
          <button
            v-for="option in filteredTasks"
            :key="option.id"
            type="button"
            class="picker-item"
            @click="selectTask(option.id); taskPickerVisible = false"
          >
            <div class="row--between">
              <strong class="code">{{ option.code }}</strong>
              <span class="small muted">可调 {{ formatTargetValue(option.adjustableAmount ?? null, primaryTaskIndicator(option)?.statCaliber) }}</span>
            </div>
            <div class="small ellipsis">{{ primaryTaskIndicator(option)?.name ?? '—' }}</div>
            <div class="small muted">{{ option.unit?.name ?? '—' }}</div>
          </button>
          <div v-if="filteredTasks.length === 0" class="empty-state">没有可调整的任务</div>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="typePickerVisible" position="bottom" round>
      <div class="picker-sheet simple">
        <div class="sheet-head">
          <strong>选择调整类型</strong>
          <button type="button" class="close-btn" @click="typePickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <button
          v-for="option in typeOptions"
          :key="option.type"
          type="button"
          class="type-option"
          :disabled="!option.enabled"
          @click="setType(option.type)"
        >
          <div class="row--between">
            <strong>{{ typeLabel(option.type) }}</strong>
            <van-icon v-if="form.type === option.type" name="success" color="#2B5BC0" />
          </div>
          <div v-if="option.reason" class="small muted">{{ option.reason }}</div>
        </button>
      </div>
    </van-popup>

    <van-popup v-model:show="fromYearVisible" position="bottom" round>
      <van-picker
        :columns="fromYearOptions.map((year) => ({ text: `${year} 年`, value: year }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: number }> }) => {
          form.fromYear = value.selectedOptions[0]?.value ?? null
          form.toYear = null
          fromYearVisible = false
        }"
        @cancel="fromYearVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="toYearVisible" position="bottom" round>
      <van-picker
        :columns="toYearOptions.map((year) => ({ text: `${year} 年`, value: year }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: number }> }) => {
          form.toYear = value.selectedOptions[0]?.value ?? null
          toYearVisible = false
        }"
        @cancel="toYearVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="unitPickerVisible" position="bottom" round :style="{ height: '65vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择接收单元</strong>
          <button type="button" class="close-btn" @click="unitPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button
            v-for="unit in receiverOptions"
            :key="unit.id"
            type="button"
            class="picker-item"
            @click="form.toUnitId = unit.id; unitPickerVisible = false"
          >{{ unit.code }} {{ unit.name }}</button>
          <div v-if="receiverOptions.length === 0" class="empty-state">暂无可接收单元</div>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="indicatorPickerVisible" position="bottom" round :style="{ height: '70vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择指标</strong>
          <button type="button" class="close-btn" @click="indicatorPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button
            v-for="item in indicatorOptions"
            :key="item.id"
            type="button"
            class="picker-item"
            @click="tcForm.indicatorId = item.id; indicatorPickerVisible = false"
          >{{ item.label }}</button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="tcYearVisible" position="bottom" round>
      <van-picker
        :columns="tcYearOptions.map((year) => ({ text: `${year} 年`, value: year }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: number }> }) => {
          tcForm.year = value.selectedOptions[0]?.value ?? null
          tcYearVisible = false
        }"
        @cancel="tcYearVisible = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.page-head {
  margin: 2px 0 10px;
}
.page-head h1 {
  margin: 0;
  font-size: 21px;
}
.page-head p {
  margin: 3px 0 0;
}
.card {
  margin-top: 12px;
}
.field {
  margin-bottom: 12px;
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
.task-summary {
  padding: 10px;
  margin-bottom: 10px;
  border-radius: 8px;
  background: #f8fafd;
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-weight: 600;
}
.picker-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 14px calc(14px + var(--safe-bottom));
}
.picker-sheet.simple {
  height: auto;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
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
.picker-list {
  flex: 1;
  overflow-y: auto;
  margin-top: 6px;
}
.picker-item {
  width: 100%;
  min-height: 56px;
  padding: 8px 6px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
.type-option {
  width: 100%;
  min-height: 58px;
  padding: 10px;
  margin-bottom: 8px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
.type-option:disabled {
  opacity: 0.5;
}
.error-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fdecec;
  color: var(--danger);
  font-size: 12px;
  line-height: 18px;
}
.compare-group {
  margin-top: 10px;
}
.cell-changed {
  background: #fdf6ec;
}
.conservation {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 6px;
}
</style>
