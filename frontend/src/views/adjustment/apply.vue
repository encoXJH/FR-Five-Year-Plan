<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdjustmentCompareTable from './components/AdjustmentCompareTable.vue'
import MaterialUploader from '@/views/submission/components/MaterialUploader.vue'
import { createAdjustment, previewAdjustment } from '@/api/adjustment'
import { getTask, listTasks } from '@/api/task'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { listPlans } from '@/api/plan'
import { listIndicators } from '@/api/indicator'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { ApiError } from '@/utils/request'
import { enumLabel } from '@/utils/enumLabels'
import {
  adjustmentTypeOptions,
  checkTargetYear,
  isTransferType,
  summarizeItems,
} from '@/utils/adjustment'
import { formatTargetValue, hasQuantitativeFields, isPercentCaliber } from '@/utils/indicator'
import { canAssignNewB, percentToStore } from '@/utils/allocation'
import { canWriteTask } from '@/utils/task'
import type { FieldError } from '@/types/api'
import type { PlanCard } from '@/types/plan'
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

// ---------- B 任务选择 ----------
const taskOptions = ref<TaskListItem[]>([])
const taskLoading = ref(false)
const task = ref<TaskDetail | null>(null)

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

const caliber = computed<StatCaliber | null>(() => task.value?.indicator?.statCaliber ?? null)
const isBoolean = computed(() => caliber.value === 'BOOLEAN')
const isTargetChange = computed(() => form.type === 'TARGET_CHANGE')
const isHistoricalTask = computed(
  () => !isTargetChange.value && task.value?.isHistorical === true,
)
const formReadonly = computed(() => step.value === 2 || isHistoricalTask.value)

/** 类型选项按口径过滤（70006 引导；TARGET_CHANGE 仅管理员） */
const typeOptions = computed(() => adjustmentTypeOptions(caliber.value, isAdmin.value))

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

async function selectTask(taskId: string): Promise<void> {
  form.taskId = taskId
  task.value = null
  form.fromYear = null
  form.amount = undefined
  form.toYear = null
  form.toUnitId = ''
  preview.value = null
  if (!taskId) return
  try {
    task.value = await getTask(taskId)
    const planYear = fromYearOptions.value
    if (isBoolean.value && planYear.length === 1) {
      form.fromYear = planYear[0]
    }
  } catch {
    // 拦截器已提示
  }
}

// ---------- TARGET_CHANGE（指标级，仅管理员） ----------
const planOptions = ref<PlanCard[]>([])
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

async function selectTcPlan(planId: string): Promise<void> {
  tcForm.planId = planId
  tcForm.indicatorId = ''
  tcForm.year = null
  indicatorOptions.value = []
  if (!planId) return
  try {
    const tree = await listIndicators(planId)
    const options: { id: string; label: string; caliber: StatCaliber }[] = []
    for (const node of tree) {
      if (hasQuantitativeFields(node.level, node.isQuantifiable) && node.statCaliber) {
        options.push({ id: node.id, label: `${node.code} ${node.name}`, caliber: node.statCaliber })
      }
      for (const child of node.children ?? []) {
        if (hasQuantitativeFields(child.level, child.isQuantifiable) && child.statCaliber) {
          options.push({
            id: child.id,
            label: `${child.code} ${child.name}`,
            caliber: child.statCaliber,
          })
        }
      }
    }
    indicatorOptions.value = options
  } catch {
    // 拦截器已提示
  }
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
    if (!tcForm.indicatorId) errors.push('请选择要调整的 A / A+ 指标')
    if (tcForm.year === null) errors.push('请选择目标年度')
    if (!tcIsBoolean.value && !tcIsPercent.value && tcForm.target === undefined) {
      errors.push('请填写新的目标值')
    }
    if (!tcIsBoolean.value && tcIsPercent.value && tcForm.percentTarget === undefined) {
      errors.push('请填写新的目标百分比')
    }
  } else {
    if (!form.taskId) errors.push('请选择 B 任务')
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
    ElMessage.warning('请等待 B 任务详情加载完成')
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
    task.value?.indicator?.unitLabel,
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
    ElMessage.success('调整申请已提交，等待校级负责人初审')
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

onMounted(() => {
  taskLoading.value = true
  listTasks({ page: 1, pageSize: 200, isHistorical: false })
    .then((page) => (taskOptions.value = page.items.filter((item) => canWriteTask(item))))
    .catch(() => undefined)
    .finally(() => (taskLoading.value = false))
  listOrgUnits()
    .then((list) => (unitOptions.value = list))
    .catch(() => undefined)
  if (isAdmin.value) {
    listPlans()
      .then((list) => (planOptions.value = list))
      .catch(() => undefined)
  }
  const presetTaskId = typeof route.query.taskId === 'string' ? route.query.taskId : ''
  if (presetTaskId) {
    selectTask(presetTaskId).finally(() => {
      watchReady.value = true
    })
  } else {
    watchReady.value = true
  }
})
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1 class="page-title">发起调整申请</h1>
        <p class="page-subtitle">延期 / 转移 / 目标调整；审批通过后生效，全程留痕</p>
      </div>
      <el-button @click="router.push('/adjustments')">返回列表</el-button>
    </div>

    <el-steps :active="step - 1" align-center class="apply-steps" finish-status="success">
      <el-step title="填写申请信息" />
      <el-step title="试算对比与提交" />
    </el-steps>

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
          <el-form-item v-if="!isTargetChange" label="B 任务" required>
            <el-select
              v-model="form.taskId"
              filterable
              :loading="taskLoading"
              placeholder="选择要调整的任务"
              style="width: 100%"
              :disabled="step === 2"
              @change="selectTask"
            >
              <el-option
                v-for="option in taskOptions"
                :key="option.id"
                :label="`${option.code} ${option.indicator?.name ?? ''}`"
                :value="option.id"
              />
            </el-select>
          </el-form-item>

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
              <span v-if="task?.indicator?.unitLabel" class="field-unit">
                {{ task.indicator.unitLabel }}
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
              <div class="field-tip">接收方原本没有该 A+ 的 B 任务时，生效后系统自动新建</div>
            </el-form-item>
          </template>

          <!-- TARGET_CHANGE（指标级，仅管理员） -->
          <template v-else-if="isTargetChange">
            <el-form-item label="所属计划" required>
              <el-select
                v-model="tcForm.planId"
                placeholder="选择计划"
                style="width: 100%"
                :disabled="formReadonly"
                @change="selectTcPlan"
              >
                <el-option
                  v-for="plan in planOptions"
                  :key="plan.id"
                  :label="plan.name"
                  :value="plan.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="调整指标" required>
              <el-select
                v-model="tcForm.indicatorId"
                filterable
                placeholder="选择 A / A+ 指标"
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
            :years="planYears"
            :caliber="caliber ?? 'INCREMENT'"
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
            审批链：校级负责人初审 → {{ isTransferType(form.type as AdjustmentType) ? '接收方确认 → ' : '' }}校领导审批；审批期间调整额度将被冻结（70004）
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

.form-card,
.preview-card {
  border-radius: var(--radius-card);
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
}
</style>
