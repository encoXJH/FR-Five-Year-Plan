<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createIndicator,
  getIndicator,
  updateIndicator,
  updateIndicatorDirection,
  updateYearTargets,
} from '@/api/indicator'
import { getUserOptions } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { CALIBER_BY_MEASURE, isPercentCaliber, sumYearTargets } from '@/utils/indicator'
import { formatNumber } from '@/utils/format'
import type {
  CreateIndicatorRequest,
  Indicator,
  IndicatorDetail,
  UpdateIndicatorRequest,
  YearTarget,
} from '@/types/indicator'
import type { MeasureType, StatCaliber } from '@/types/enums'
import type { UserOption } from '@/types/user'

export interface IndicatorDirectionOption {
  id: string
  name?: string
  label?: string
  code?: string | null
}

const props = withDefaults(defineProps<{
  modelValue: boolean
  planId: string
  indicator: Indicator | null
  years: number[]
  directionOptions?: IndicatorDirectionOption[]
  planArchived?: boolean
}>(), {
  directionOptions: () => [],
  planArchived: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const userStore = useUserStore()
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))
const workingIndicator = ref<Indicator | IndicatorDetail | null>(null)
const isEdit = computed(() => workingIndicator.value !== null)
const title = computed(() =>
  isEdit.value ? `编辑指标 ${workingIndicator.value?.code ?? ''}` : '新增指标',
)
const historical = computed(() => {
  const source = workingIndicator.value
  if (!source) return false
  const childCount = 'children' in source ? (source.children?.length ?? 0) : 0
  if (source.level !== 'A' || childCount > 0) return true
  return 'bSummaries' in source && source.bSummaries.some((item) => item.isHistorical)
})
const distributed = computed(() => workingIndicator.value?.status === 'DISTRIBUTED')
const formReadOnly = computed(() => {
  const source = workingIndicator.value
  return historical.value
    || distributed.value
    || props.planArchived
    || (source !== null && 'editable' in source && !source.editable)
})

interface FormState {
  name: string
  nature: 'CONSTRAINT' | 'DEVELOPMENT'
  directionId: string | null
  isQuantifiable: boolean
  measureType: MeasureType
  statCaliber: StatCaliber
  unitLabel: string
  targetDirection: 'HIGHER_BETTER' | 'LOWER_BETTER'
  rollupMode: 'AUTO_SUM' | 'MANUAL'
  baselineValue: number | null
  totalTarget: number | null
  planYear: number | null
  ownerId: string | null
  description: string
  evidenceRequirement: string
  minFileCount: number
}

const formRef = ref<FormInstance>()
const saving = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const userOptions = ref<UserOption[]>([])
const form = reactive<FormState>({
  name: '',
  nature: 'CONSTRAINT',
  directionId: null,
  isQuantifiable: true,
  measureType: 'NUMBER',
  statCaliber: 'INCREMENT',
  unitLabel: '',
  targetDirection: 'HIGHER_BETTER',
  rollupMode: 'AUTO_SUM',
  baselineValue: null,
  totalTarget: null,
  planYear: null,
  ownerId: null,
  description: '',
  evidenceRequirement: '',
  minFileCount: 0,
})
const targetInputs = reactive<Record<number, number | null>>({})
let openRequestId = 0

const quantitative = computed(() => form.isQuantifiable)
const caliberOptions = computed(() =>
  CALIBER_BY_MEASURE[form.measureType].map((value) => ({
    value,
    label: enumLabel('StatCaliber', value, userStore.enums),
  })),
)
const showBaseline = computed(() => form.statCaliber === 'STOCK' || form.statCaliber === 'GROWTH')
const showTotalTarget = computed(() =>
  form.statCaliber !== 'BOOLEAN' && form.statCaliber !== 'RATIO' && form.statCaliber !== 'DIRECT_PERCENT',
)
const showUnit = computed(() => form.statCaliber !== 'BOOLEAN')
const directionSelectOptions = computed(() => {
  const options = props.directionOptions.map((option) => ({
    ...option,
    text: option.name || option.label
      ? option.name ?? option.label
      : option.code ?? option.id,
  }))
  if (form.directionId && !options.some((option) => option.id === form.directionId)) {
    options.unshift({ id: form.directionId, text: '当前建设方向' })
  }
  return options
})
const rules: FormRules = {
  name: [{ required: true, message: '请填写指标名称', trigger: 'blur' }],
  measureType: [{ required: true, message: '请选择计量类型', trigger: 'change' }],
  statCaliber: [{ required: true, message: '请选择统计口径', trigger: 'change' }],
  unitLabel: [{ required: true, whitespace: true, message: '请填写计量单位', trigger: 'blur' }],
  planYear: [{ required: true, message: '请选择计划完成年份', trigger: 'change' }],
}

function resetForm(): void {
  const source = workingIndicator.value
  form.name = source?.name ?? ''
  form.nature = source?.nature ?? 'CONSTRAINT'
  // 新建时仅 ADMIN 默认带首个方向；非 ADMIN 留空，避免创建接口 requireAdmin 返回 403。
  form.directionId = source
    ? (source.directionId ?? null)
    : (isAdmin.value ? (props.directionOptions[0]?.id ?? null) : null)
  form.isQuantifiable = source?.isQuantifiable ?? true
  form.measureType = source?.measureType ?? 'NUMBER'
  form.statCaliber = source?.statCaliber ?? 'INCREMENT'
  form.unitLabel = source?.unitLabel ?? ''
  form.targetDirection = source?.targetDirection ?? 'HIGHER_BETTER'
  form.rollupMode = source?.rollupMode ?? 'AUTO_SUM'
  form.baselineValue = source?.baselineValue ?? null
  form.totalTarget = source?.totalTarget ?? null
  form.planYear = source?.yearTargets?.find((item) => item.target === true)?.year ?? null
  form.ownerId = source?.owner?.id ?? null
  form.description = source?.description ?? ''
  form.evidenceRequirement = source?.evidenceRequirement ?? ''
  form.minFileCount = source?.minFileCount ?? 0
  for (const key of Object.keys(targetInputs)) delete targetInputs[Number(key)]
  for (const year of props.years) {
    const target = source?.yearTargets?.find((item) => item.year === year)?.target
    targetInputs[year] = typeof target === 'number'
      ? (isPercentCaliber(form.statCaliber) ? target * 100 : target)
      : null
  }
}

function loadOptions(): void {
  if (userOptions.value.length === 0) {
    getUserOptions().then((list) => {
      userOptions.value = list
    }).catch(() => undefined)
  }
}

async function initializeDrawer(): Promise<void> {
  const requestId = ++openRequestId
  // 复位加载态：旧详情请求的 finally 因 requestId 不匹配不会清理，需在此显式重置。
  detailLoading.value = false
  detailError.value = ''
  workingIndicator.value = props.indicator
  resetForm()
  loadOptions()
  await nextTick()
  formRef.value?.clearValidate()
  if (!props.indicator) return

  detailLoading.value = true
  try {
    const detail = await getIndicator(props.indicator.id)
    if (requestId !== openRequestId) return
      workingIndicator.value = detail
    resetForm()
    await nextTick()
    formRef.value?.clearValidate()
  } catch {
    if (requestId === openRequestId) detailError.value = '完整指标详情加载失败，请重试后再保存'
    return
  } finally {
      if (requestId === openRequestId) detailLoading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void initializeDrawer()
    } else {
      // 关闭时使旧请求失效并复位加载态，避免紧接着打开新增时被旧 loading 卡住。
      openRequestId += 1
      detailLoading.value = false
    }
  },
)

function onMeasureTypeChange(): void {
  const allowed = CALIBER_BY_MEASURE[form.measureType]
  if (!allowed.includes(form.statCaliber)) form.statCaliber = allowed[0]
}

/** 把五年总量按年度平均分配；最后一年补足舍入差额，保证各年之和等于总量 */
function distributeTotalEvenly(): void {
  const total = form.totalTarget
  if (total === null || props.years.length === 0) return
  const scale = 100
  const totalUnits = Math.round(total * scale)
  const base = Math.trunc(totalUnits / props.years.length)
  const remainder = totalUnits - base * props.years.length
  props.years.forEach((year, index) => {
    const units = base + (index === props.years.length - 1 ? remainder : 0)
    targetInputs[year] = units / scale
  })
}

function buildYearTargets(): YearTarget[] | undefined {
  if (!quantitative.value) return undefined
  if (form.statCaliber === 'BOOLEAN') {
    return props.years.map((year) => ({ year, target: year === form.planYear ? true : null }))
  }
  return props.years.map((year) => ({
    year,
    target: targetInputs[year] == null
      ? null
      : (isPercentCaliber(form.statCaliber)
        ? Number((targetInputs[year]! / 100).toFixed(6))
        : targetInputs[year]),
  }))
}

const yearSum = computed(() => sumYearTargets(buildYearTargets() ?? []))
const sumDiff = computed(() => {
  if (form.totalTarget === null) return null
  const diff = yearSum.value - form.totalTarget
  return Math.abs(diff) < 0.005 ? 0 : Math.round(diff * 100) / 100
})

function quantitativePayload() {
  if (!quantitative.value) {
    return {
      measureType: null,
      statCaliber: null,
      unitLabel: null,
      targetDirection: null,
      rollupMode: null,
      baselineValue: null,
      evidenceRequirement: null,
      minFileCount: null,
    } as const
  }
  return {
    measureType: form.measureType,
    statCaliber: form.statCaliber,
    unitLabel: showUnit.value ? form.unitLabel || null : null,
    targetDirection: form.targetDirection,
    rollupMode: form.rollupMode,
    baselineValue: showBaseline.value ? form.baselineValue : null,
    evidenceRequirement: form.evidenceRequirement || null,
    minFileCount: form.minFileCount ?? 0,
  }
}

async function submit(): Promise<void> {
  if (!formRef.value || saving.value || detailLoading.value || detailError.value || formReadOnly.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  saving.value = true
  let stage = isEdit.value ? '指标保存' : '指标创建'
  try {
    const base = {
      name: form.name.trim(),
      nature: form.nature,
      description: form.description.trim() || null,
      ownerId: form.ownerId,
      ...quantitativePayload(),
    }
    if (workingIndicator.value) {
      // 普通 PATCH 不接受 directionId；方向变化仅由 ADMIN 通过独立接口修改。
      const previousDirectionId = workingIndicator.value.directionId ?? null
      const payload: UpdateIndicatorRequest = {
        ...base,
        version: workingIndicator.value.version,
      }
      payload.isQuantifiable = form.isQuantifiable
      workingIndicator.value = await updateIndicator(workingIndicator.value.id, payload)
      if (isAdmin.value && form.directionId !== previousDirectionId) {
        stage = '建设方向保存'
        workingIndicator.value = await updateIndicatorDirection(
          workingIndicator.value.id,
          form.directionId,
          workingIndicator.value.version,
        )
      }
    } else {
      const payload: CreateIndicatorRequest = {
        ...base,
        level: 'A',
        isQuantifiable: form.isQuantifiable,
        totalTarget: null,
        // 非 ADMIN 传 directionId 会被后端 requireAdmin 拒绝，故仅 ADMIN 提交。
        ...(isAdmin.value ? { directionId: form.directionId } : {}),
      }
      workingIndicator.value = await createIndicator(props.planId, payload)
    }

    if (quantitative.value && workingIndicator.value) {
      stage = '年度目标保存'
      workingIndicator.value = await updateYearTargets(workingIndicator.value.id, {
        totalTarget: showTotalTarget.value ? form.totalTarget : null,
        yearTargets: buildYearTargets() ?? [],
        version: workingIndicator.value.version,
      })
    }

    ElMessage.success(quantitative.value ? '指标与年度目标已保存' : '指标已保存')
    emit('update:modelValue', false)
    emit('saved')
  } catch (error) {
    const reason = error instanceof Error && error.message ? `：${error.message}` : ''
    ElMessage.error(`${stage}失败${reason}`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="min(1120px, calc(100vw - 24px))"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="detailLoading" class="indicator-edit-content">
      <el-alert
        v-if="detailError"
        type="error"
        :closable="false"
        class="drawer-alert"
        :title="detailError"
      >
        <el-button link type="primary" @click="initializeDrawer">重新加载</el-button>
      </el-alert>
      <el-alert
        v-else-if="historical"
        type="info"
        :closable="false"
        class="drawer-alert"
        title="该指标含历史分解数据，基础信息只读"
      />
      <el-alert
        v-else-if="distributed"
        type="info"
        :closable="false"
        class="drawer-alert"
        title="该指标已下发，基础信息只读；目标变更请发起调整申请"
      />
      <el-alert
        v-else-if="planArchived"
        type="info"
        :closable="false"
        class="drawer-alert"
        title="该计划已归档，指标只读"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :disabled="formReadOnly || detailLoading"
        label-width="110px"
        class="indicator-form"
      >
        <el-form-item label="指标名称" prop="name">
          <el-input v-model="form.name" maxlength="200" show-word-limit placeholder="关键产出指标名称" />
        </el-form-item>

        <el-form-item label="指标性质" prop="nature">
          <el-radio-group v-model="form.nature">
            <el-radio value="CONSTRAINT">{{ enumLabel('IndicatorNature', 'CONSTRAINT', userStore.enums) }}</el-radio>
            <el-radio value="DEVELOPMENT">{{ enumLabel('IndicatorNature', 'DEVELOPMENT', userStore.enums) }}</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="主要建设方向" prop="directionId">
          <el-select
            v-model="form.directionId"
            clearable
            filterable
            placeholder="请选择主要建设方向"
            style="width: 100%"
            :disabled="formReadOnly || !isAdmin || directionSelectOptions.length === 0"
          >
            <el-option
              v-for="option in directionSelectOptions"
              :key="option.id"
              :label="option.text"
              :value="option.id"
            />
          </el-select>
          <div v-if="!isAdmin" class="field-tip">仅平台管理员可修改主要建设方向</div>
          <div v-else-if="directionOptions.length === 0" class="field-tip">
            当前未提供建设方向选项，保留已有归属；由父级传入后即可选择
          </div>
        </el-form-item>

        <el-form-item label="是否可量化">
          <el-switch v-model="form.isQuantifiable" />
          <div class="field-tip">不可量化 A 仅维护基础信息，不生成 B，也不进入填报、审核、核算和完成率统计</div>
        </el-form-item>

        <template v-if="quantitative">
          <el-form-item label="计量类型" prop="measureType">
            <el-select v-model="form.measureType" style="width: 100%" @change="onMeasureTypeChange">
              <el-option
                v-for="option in enumOptions('MeasureType', userStore.enums)"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="统计口径" prop="statCaliber">
            <el-select v-model="form.statCaliber" style="width: 100%">
              <el-option
                v-for="option in caliberOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <div class="field-tip">计量类型与统计口径必须匹配，否则服务端返回 40007</div>
          </el-form-item>

          <el-form-item v-if="showUnit" label="计量单位" prop="unitLabel">
            <el-input v-model="form.unitLabel" placeholder="如：项、个、人、门" style="width: 160px" />
          </el-form-item>

          <el-form-item label="达成方向" prop="targetDirection">
            <el-radio-group v-model="form.targetDirection">
              <el-radio value="HIGHER_BETTER">{{ enumLabel('TargetDirection', 'HIGHER_BETTER', userStore.enums) }}</el-radio>
              <el-radio value="LOWER_BETTER">{{ enumLabel('TargetDirection', 'LOWER_BETTER', userStore.enums) }}</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="汇总方式" prop="rollupMode">
            <el-radio-group v-model="form.rollupMode">
              <el-radio value="AUTO_SUM">{{ enumLabel('RollupMode', 'AUTO_SUM', userStore.enums) }}</el-radio>
              <el-radio value="MANUAL">{{ enumLabel('RollupMode', 'MANUAL', userStore.enums) }}</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item v-if="showBaseline" label="基线值">
            <el-input-number v-model="form.baselineValue" controls-position="right" :precision="2" />
            <div class="field-tip">基线年的实际值，用于存量 / 增长率计算</div>
          </el-form-item>

          <el-form-item v-if="showTotalTarget" label="五年总量">
            <div class="total-target-row">
              <el-input-number v-model="form.totalTarget" :min="0" controls-position="right" :precision="2" />
              <el-button
                v-if="form.statCaliber === 'INCREMENT'"
                size="small"
                :disabled="formReadOnly || form.totalTarget === null"
                title="把五年总量按年度数量平均分配"
                @click="distributeTotalEvenly"
              >
                平均分配
              </el-button>
            </div>
          </el-form-item>

          <el-form-item v-if="form.statCaliber === 'BOOLEAN'" label="计划完成年" prop="planYear">
            <el-radio-group v-model="form.planYear">
              <el-radio v-for="year in years" :key="year" :value="year">{{ year }} 年</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item v-else label="各年度目标">
            <div class="year-target-grid">
              <label v-for="year in years" :key="year" class="year-target-field">
                <span>{{ year }}</span>
                <el-input-number
                  v-model="targetInputs[year]"
                  :min="0"
                  :precision="isPercentCaliber(form.statCaliber) ? 1 : 2"
                  controls-position="right"
                />
              </label>
            </div>
            <div
              v-if="form.statCaliber === 'INCREMENT'"
              class="field-tip"
              :class="sumDiff === 0 ? 'sum-ok' : 'sum-error'"
            >
              各年度之和 {{ formatNumber(yearSum) }}，五年总量 {{ formatNumber(form.totalTarget) }}
              <template v-if="sumDiff !== null">（{{ sumDiff === 0 ? '0 ✓' : `差值 ${sumDiff > 0 ? '+' : ''}${sumDiff}` }}）</template>
              ；增量累计型要求两者相等（40002）
            </div>
          </el-form-item>
        </template>

        <el-form-item label="项目负责人">
          <el-select
            v-model="form.ownerId"
            placeholder="请选择校级项目负责人"
            filterable
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="user in userOptions"
              :key="user.id"
              :label="`${user.name}（${user.username}）`"
              :value="user.id"
            />
          </el-select>
          <div class="field-tip">留空默认由本人负责</div>
        </el-form-item>

        <el-form-item label="填写说明">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" show-word-limit />
        </el-form-item>

        <el-form-item v-if="quantitative" label="材料考核要求">
          <el-input
            v-model="form.evidenceRequirement"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="佐证材料要求，如：获奖文件、证书扫描件"
          />
        </el-form-item>

        <el-form-item v-if="quantitative" label="最少材料数" prop="minFileCount">
          <el-input-number v-model="form.minFileCount" :min="0" :precision="0" controls-position="right" />
          <div class="field-tip">每次填报需上传的佐证材料最少份数，0 表示不强制</div>
        </el-form-item>
      </el-form>

    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ formReadOnly ? '关闭' : '取消' }}</el-button>
      <el-button
        v-if="!formReadOnly"
        type="primary"
        :loading="saving"
        :disabled="detailLoading || Boolean(detailError)"
        @click="submit"
      >
        {{ isEdit ? '保存修改' : '创建指标' }}
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.indicator-edit-content {
  min-height: 120px;
}

.indicator-form {
  max-width: 860px;
}

.drawer-alert {
  margin-bottom: var(--space-4);
}

.field-tip {
  font-size: 12px;
  line-height: 20px;
  color: var(--el-text-color-secondary);
}

.total-target-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.year-target-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2);
  width: 100%;
}

.year-target-field {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: 12px;
  font-weight: 400;
}

.year-target-field :deep(.el-input-number) {
  width: 100%;
}

.sum-ok {
  color: var(--el-color-success);
}

.sum-error {
  color: var(--el-color-danger);
}

@media (max-width: 600px) {
  .year-target-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
