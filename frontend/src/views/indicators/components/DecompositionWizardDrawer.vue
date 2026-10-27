<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { decomposeIndicator, getAllocation, saveAllocation } from '@/api/allocation'
import { getIndicator } from '@/api/indicator'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { getUserOptions } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { newIdempotencyKey } from '@/utils/idempotency'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { formatDiff } from '@/utils/format'
import {
  booleanPlanYear,
  CALIBER_BY_MEASURE,
  formatTargetValue,
  sumYearTargets,
} from '@/utils/indicator'
import {
  canAssignNewB,
  computeYearSummary,
  isSummableCaliber,
  isSummaryBalanced,
  percentToDisplay,
  percentToStore,
  rowBooleanPlanYear,
  rowHasTarget,
  rowsMissingOwner,
  rowTotalTarget,
} from '@/utils/allocation'
import type { MatrixRow, MatrixTargets } from '@/utils/allocation'
import type {
  Allocation,
  AllocationYearSummary,
  DecompositionRequest,
} from '@/types/allocation'
import type {
  IndicatorDetail,
  IndicatorTreeNode,
  YearTarget,
} from '@/types/indicator'
import type {
  IndicatorNature,
  MeasureType,
  StatCaliber,
  TargetDirection,
} from '@/types/enums'
import type { UserOption } from '@/types/user'

const NEW_A_PLUS = '__new__'

const props = defineProps<{
  modelValue: boolean
  planId: string
  parent: IndicatorTreeNode | null
  initialAPlusId: string | null
  allowCreate: boolean
  years: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [payload: { parentId: string; aPlusId: string }]
}>()

const userStore = useUserStore()
const viewportWidth = ref(window.innerWidth)
const descriptionColumns = computed(() => viewportWidth.value <= 600 ? 1 : viewportWidth.value <= 900 ? 2 : 3)
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

interface IndicatorFormState {
  name: string
  nature: IndicatorNature
  measureType: MeasureType
  statCaliber: StatCaliber
  unitLabel: string
  targetDirection: TargetDirection
  baselineValue: number | null
  totalTarget: number | null
  planYear: number | null
  ownerId: string | null
  description: string
  evidenceRequirement: string
  targets: MatrixTargets
}

const formRef = ref<FormInstance>()
const form = reactive<IndicatorFormState>({
  name: '',
  nature: 'CONSTRAINT',
  measureType: 'NUMBER',
  statCaliber: 'INCREMENT',
  unitLabel: '',
  targetDirection: 'HIGHER_BETTER',
  baselineValue: null,
  totalTarget: null,
  planYear: null,
  ownerId: null,
  description: '',
  evidenceRequirement: '',
  targets: {},
})

const rules: FormRules = {
  name: [{ required: true, message: '请填写指标名称', trigger: 'blur' }],
  measureType: [{ required: true, message: '请选择计量类型', trigger: 'change' }],
  statCaliber: [{ required: true, message: '请选择统计口径', trigger: 'change' }],
  planYear: [{ required: true, message: '请选择计划完成年份', trigger: 'change' }],
}

const selectedId = ref(NEW_A_PLUS)
const currentAPlus = ref<IndicatorDetail | null>(null)
const loading = ref(false)
const loadFailed = ref(false)
const saving = ref(false)
const dirty = ref(false)
const initialized = ref(false)
const suppressDirty = ref(false)
const advancedActive = ref<string[]>([])
const decompositionKey = ref(newIdempotencyKey())

const userOptions = ref<UserOption[]>([])
const unitOptions = ref<OrgUnit[]>([])
const optionsLoaded = ref(false)
const optionsLoading = ref(false)

const rows = ref<MatrixRow[]>([])
const allocationVersion = ref(0)
const addRowUnitId = ref('')

const isNew = computed(() => selectedId.value === NEW_A_PLUS)
const title = computed(() => {
  const parent = props.parent
  return parent ? `分解任务 · ${parent.code} ${parent.name}` : '分解任务'
})
const draftAPlusOptions = computed(() =>
  (props.parent?.children ?? []).filter(
    (item) => item.level === 'A_PLUS' && item.status === 'DRAFT',
  ),
)
const caliber = computed<StatCaliber>(() =>
  isNew.value ? form.statCaliber : (currentAPlus.value?.statCaliber ?? 'INCREMENT'),
)
const unitLabel = computed(() =>
  isNew.value ? form.unitLabel : (currentAPlus.value?.unitLabel ?? null),
)
const summable = computed(() => isSummableCaliber(caliber.value))
const isBoolean = computed(() => caliber.value === 'BOOLEAN')
const isPercent = computed(() => caliber.value === 'RATIO' || caliber.value === 'DIRECT_PERCENT')
const showBaseline = computed(() => form.statCaliber === 'STOCK' || form.statCaliber === 'GROWTH')
const showTotalTarget = computed(
  () => !['BOOLEAN', 'RATIO', 'DIRECT_PERCENT'].includes(form.statCaliber),
)
const showUnit = computed(() => form.statCaliber !== 'BOOLEAN')
const caliberOptions = computed(() =>
  CALIBER_BY_MEASURE[form.measureType].map((value) => ({
    value,
    label: enumLabel('StatCaliber', value, userStore.enums),
  })),
)

const indicatorTargets = computed<MatrixTargets>(() => {
  if (isNew.value) return form.targets
  const targets: MatrixTargets = {}
  for (const item of currentAPlus.value?.yearTargets ?? []) {
    targets[item.year] = item.target
  }
  return targets
})
const liveSummary = computed(() =>
  computeYearSummary(rows.value, props.years, indicatorTargets.value),
)
const balanced = computed(() => summable.value && isSummaryBalanced(liveSummary.value))
const missingOwners = computed(() => rowsMissingOwner(rows.value))
const addableUnits = computed(() => {
  const used = new Set(rows.value.map((row) => row.unitId))
  return unitOptions.value.filter((unit) => canAssignNewB(unit) && !used.has(unit.id))
})

const yearTargetSum = computed(() => sumYearTargets(buildIndicatorYearTargets()))
const yearSumDiff = computed(() =>
  form.totalTarget === null ? null : yearTargetSum.value - form.totalTarget,
)

watch(form, () => {
  if (initialized.value && !suppressDirty.value) dirty.value = true
}, { deep: true })
watch(rows, () => {
  if (initialized.value && !suppressDirty.value) dirty.value = true
}, { deep: true })

function resetTargets(targets: MatrixTargets): void {
  for (const key of Object.keys(targets)) delete targets[Number(key)]
  for (const year of props.years) targets[year] = null
}

function resetNewForm(): void {
  const parent = props.parent
  const measureType = parent?.measureType ?? 'NUMBER'
  const allowed = CALIBER_BY_MEASURE[measureType]
  form.name = ''
  form.nature = parent?.nature ?? 'CONSTRAINT'
  form.measureType = measureType
  form.statCaliber = parent?.statCaliber && allowed.includes(parent.statCaliber)
    ? parent.statCaliber
    : allowed[0]
  form.unitLabel = parent?.unitLabel ?? ''
  form.targetDirection = parent?.targetDirection ?? 'HIGHER_BETTER'
  form.baselineValue = parent?.baselineValue ?? null
  form.totalTarget = null
  form.planYear = null
  form.ownerId = parent?.owner?.id ?? null
  form.description = ''
  form.evidenceRequirement = ''
  resetTargets(form.targets)
  advancedActive.value = []
}

async function ensureOptions(): Promise<void> {
  if (optionsLoaded.value || optionsLoading.value) return
  optionsLoading.value = true
  try {
    const [users, units] = await Promise.all([getUserOptions(), listOrgUnits()])
    userOptions.value = users
    unitOptions.value = units
    optionsLoaded.value = true
  } catch {
    userOptions.value = []
    unitOptions.value = []
  } finally {
    optionsLoading.value = false
  }
}

function normalizeTarget(
  value: number | boolean | null,
  booleanCaliber: boolean,
): number | boolean | null {
  if (booleanCaliber) return value === true || value === 1 ? true : null
  return typeof value === 'number' ? value : null
}

function rebuildRows(allocation: Allocation): void {
  const booleanCaliber = caliber.value === 'BOOLEAN'
  rows.value = (allocation.rows ?? []).map((row) => {
    const targets: MatrixTargets = {}
    for (const year of props.years) {
      const found = row.yearTargets?.find((item) => item.year === year)
      targets[year] = found ? normalizeTarget(found.target, booleanCaliber) : null
    }
    return {
      unitId: row.unit.id,
      unitCode: row.unit.code,
      unitName: row.unit.name,
      ownerId: row.owner?.id ?? null,
      isHistorical: row.isHistorical,
      targets,
    }
  })
  allocationVersion.value = allocation.version
}

async function loadSelection(): Promise<void> {
  if (!props.parent) return
  loading.value = true
  loadFailed.value = false
  initialized.value = false
  suppressDirty.value = true
  currentAPlus.value = null
  rows.value = []
  addRowUnitId.value = ''
  dirty.value = false
  try {
    if (isNew.value) {
      resetNewForm()
      decompositionKey.value = newIdempotencyKey()
    } else {
      const [detail, allocation] = await Promise.all([
        getIndicator(selectedId.value),
        getAllocation(selectedId.value),
      ])
      currentAPlus.value = detail
      rebuildRows(allocation)
    }
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
    await nextTick()
    formRef.value?.clearValidate()
    suppressDirty.value = false
    initialized.value = true
    dirty.value = false
  }
}

async function initialize(): Promise<void> {
  if (!props.parent) return
  const initial = props.initialAPlusId
  const initialExists = draftAPlusOptions.value.some((item) => item.id === initial)
  selectedId.value = initialExists && initial
    ? initial
    : (draftAPlusOptions.value[0]?.id ?? NEW_A_PLUS)
  void ensureOptions()
  await loadSelection()
}

async function changeSelection(value: string): Promise<void> {
  if (value === selectedId.value) return
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('切换分工对象将丢失当前未保存的修改，确认切换？', '未保存的修改', {
        confirmButtonText: '放弃修改并切换',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  selectedId.value = value
  await loadSelection()
}

function onMeasureTypeChange(): void {
  const allowed = CALIBER_BY_MEASURE[form.measureType]
  if (!allowed.includes(form.statCaliber)) form.statCaliber = allowed[0]
  resetTargets(form.targets)
  form.totalTarget = null
  form.planYear = null
}

function onCaliberChange(): void {
  resetTargets(form.targets)
  form.totalTarget = null
  form.planYear = null
}

function buildIndicatorYearTargets(): YearTarget[] {
  return props.years.map((year) => ({
    year,
    target: form.statCaliber === 'BOOLEAN'
      ? (year === form.planYear ? true : null)
      : (form.targets[year] ?? null),
  }))
}

function buildIndicatorPayload(): DecompositionRequest['indicator'] {
  return {
    name: form.name.trim(),
    nature: form.nature,
    measureType: form.measureType,
    statCaliber: form.statCaliber,
    unitLabel: showUnit.value ? form.unitLabel.trim() || null : null,
    targetDirection: form.targetDirection,
    description: form.description.trim() || null,
    baselineValue: showBaseline.value ? form.baselineValue : null,
    totalTarget: showTotalTarget.value ? form.totalTarget : null,
    yearTargets: buildIndicatorYearTargets(),
    ownerId: form.ownerId,
    evidenceRequirement: form.evidenceRequirement.trim() || null,
  }
}

function buildRowsPayload(): DecompositionRequest['rows'] {
  return rows.value
    .map((row) => ({
      unitId: row.unitId,
      ownerId: row.ownerId,
      yearTargets: props.years.map((year) => ({
        year,
        target: row.targets[year] ?? null,
      })),
    }))
}

function numberCellValue(targets: MatrixTargets, year: number): number | undefined {
  const value = targets[year]
  return typeof value === 'number' ? value : undefined
}

function setNumberCell(targets: MatrixTargets, year: number, value: number | undefined): void {
  targets[year] = value ?? null
}

function setPercentCell(targets: MatrixTargets, year: number, value: number | undefined): void {
  targets[year] = percentToStore(value)
}

function toggleBooleanCell(row: MatrixRow, year: number, checked: boolean): void {
  for (const item of props.years) row.targets[item] = null
  row.targets[year] = checked ? true : null
}

function addRow(): void {
  const unit = addableUnits.value.find((item) => item.id === addRowUnitId.value)
  if (!unit || !canAssignNewB(unit)) return
  const targets: MatrixTargets = {}
  for (const year of props.years) targets[year] = null
  rows.value.push({
    unitId: unit.id,
    unitCode: unit.code,
    unitName: unit.name,
    ownerId: null,
    isHistorical: false,
    targets,
  })
  addRowUnitId.value = ''
}

function removeRow(index: number): void {
  const row = rows.value[index]
  if (!row || row.isHistorical) return
  rows.value.splice(index, 1)
}

function copyFromPrevYear(row: MatrixRow): void {
  const list = props.years
  for (let index = list.length - 1; index > 0; index -= 1) {
    row.targets[list[index]] = row.targets[list[index - 1]]
  }
}

function clearRow(row: MatrixRow): void {
  for (const year of props.years) row.targets[year] = null
}

function ownerName(ownerId: string | null): string {
  return ownerId
    ? (userOptions.value.find((user) => user.id === ownerId)?.name ?? '—')
    : '—'
}

function rowTotalDisplay(row: MatrixRow): string {
  if (isBoolean.value) {
    const planYear = rowBooleanPlanYear(row.targets)
    return planYear === null ? '—' : `是（${planYear} 年）`
  }
  return formatTargetValue(rowTotalTarget(row.targets), caliber.value)
}

function targetOfCurrent(year: number): number | boolean | null {
  return currentAPlus.value?.yearTargets.find((item) => item.year === year)?.target ?? null
}

function currentTotalDisplay(): string {
  const detail = currentAPlus.value
  if (!detail) return '—'
  if (detail.statCaliber === 'BOOLEAN') {
    const planYear = booleanPlanYear(detail.yearTargets)
    return planYear === null ? '—' : `是（${planYear} 年）`
  }
  return formatTargetValue(detail.totalTarget, detail.statCaliber)
}

function withUnit(value: string): string {
  return unitLabel.value ? `${value} ${unitLabel.value}` : value
}

function allocationGapText(item: AllocationYearSummary): string {
  const allocated = withUnit(formatTargetValue(item.allocatedTarget, caliber.value))
  if (item.indicatorTarget === null) {
    return `各学院已分 ${allocated}；A+ 当年目标尚未设置`
  }
  const target = withUnit(formatTargetValue(item.indicatorTarget, caliber.value))
  if (item.diff === 0) return `各学院合计 ${allocated}，正好等于 A+ 目标`
  if (item.diff !== null && item.diff < 0) {
    return `各学院已分 ${allocated}，A+ 目标 ${target}，还差 ${withUnit(formatTargetValue(-item.diff, caliber.value))}`
  }
  return `各学院已分 ${allocated}，A+ 目标 ${target}，多分了 ${withUnit(formatTargetValue(item.diff, caliber.value))}`
}

function completeSave(aPlusId: string): void {
  if (!props.parent) return
  dirty.value = false
  decompositionKey.value = newIdempotencyKey()
  ElMessage.success('分工已保存；计划未锁定，也不会通知学院')
  emit('saved', { parentId: props.parent.id, aPlusId })
  visible.value = false
}

async function saveExisting(overwrite = false): Promise<void> {
  const detail = currentAPlus.value
  if (!detail) return
  try {
    if (overwrite) {
      const latest = await getAllocation(detail.id)
      allocationVersion.value = latest.version
    }
    await saveAllocation(detail.id, {
      version: allocationVersion.value,
      rows: buildRowsPayload(),
    }, true)
    completeSave(detail.id)
  } catch (error) {
    if (error instanceof ApiError && error.code === 10005 && !overwrite) {
      await handleSaveConflict()
    } else if (error instanceof ApiError && error.code === 10005) {
      ElMessage.error('分工仍被他人修改，请稍后重试')
    } else if (error instanceof ApiError) {
      ElMessage.error(error.message || '保存分工失败')
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
  }
}

async function handleSaveConflict(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '这份分工已被他人修改。可以查看最新数据，或保留我的修改并覆盖。',
      '保存冲突（10005）',
      {
        confirmButtonText: '保留我的修改并覆盖',
        cancelButtonText: '查看最新',
        type: 'warning',
        distinguishCancelAndClose: true,
      },
    )
  } catch (action) {
    if (action === 'cancel') await loadSelection()
    return
  }
  await saveExisting(true)
}

async function submit(): Promise<void> {
  if (!props.parent || saving.value || loadFailed.value) return
  if (isNew.value) {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return
  }
  saving.value = true
  try {
    if (isNew.value) {
      const result = await decomposeIndicator(
        props.planId,
        props.parent.id,
        { indicator: buildIndicatorPayload(), rows: buildRowsPayload() },
        decompositionKey.value,
      )
      completeSave(result.aPlus.id)
    } else {
      await saveExisting()
    }
  } catch {
    // 新建请求由拦截器提示；失败重试沿用同一个幂等 Key
  } finally {
    saving.value = false
  }
}

async function confirmDiscard(): Promise<boolean> {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('有未保存的分工内容，确定离开？', '未保存的修改', {
      confirmButtonText: '放弃修改并离开',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

async function requestClose(): Promise<void> {
  if (saving.value || !(await confirmDiscard())) return
  dirty.value = false
  visible.value = false
}

function beforeClose(done: () => void): void {
  if (saving.value) return
  void confirmDiscard().then((confirmed) => {
    if (!confirmed) return
    dirty.value = false
    done()
  })
}

watch(
  () => props.modelValue,
  (value) => {
    if (value) void initialize()
  },
)

function syncViewportWidth(): void {
  viewportWidth.value = window.innerWidth
}

onMounted(() => window.addEventListener('resize', syncViewportWidth))
onBeforeUnmount(() => window.removeEventListener('resize', syncViewportWidth))
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="title"
    size="min(1180px, 100vw)"
    :close-on-click-modal="false"
    :before-close="beforeClose"
    class="decomposition-wizard"
  >
    <div v-loading="loading" class="wizard-body">
      <template v-if="parent">
        <div class="parent-context">
          <div>
            <span class="context-label">所属 A 类指标</span>
            <div class="context-title"><span class="mono">{{ parent.code }}</span> {{ parent.name }}</div>
          </div>
          <div class="context-owner">校级负责人：{{ parent.owner?.name ?? '—' }}</div>
        </div>

        <el-alert
          type="info"
          :closable="false"
          title="只保存分工，不锁定计划，也不通知学院"
        />

        <section class="wizard-section selection-section">
          <el-form label-position="top">
            <el-form-item v-if="allowCreate || draftAPlusOptions.length > 0" label="本次要分工的 A+">
              <el-select
                :model-value="selectedId"
                style="width: 100%"
                @change="changeSelection"
              >
                <el-option v-if="allowCreate" label="新建一个 A+ 指标" :value="NEW_A_PLUS" />
                <el-option
                  v-for="item in draftAPlusOptions"
                  :key="item.id"
                  :label="`继续分工：${item.code} ${item.name}`"
                  :value="item.id"
                />
              </el-select>
              <div class="field-tip">选择已有草稿只会保存学院分工，不会重复创建 A+</div>
            </el-form-item>
          </el-form>
        </section>

        <el-empty
          v-if="loadFailed"
          :image-size="80"
          description="分工数据加载失败，请重试"
        >
          <el-button type="primary" @click="loadSelection">重新加载</el-button>
        </el-empty>

        <template v-else-if="isNew || currentAPlus">
          <section class="wizard-section">
            <div class="section-heading">
              <div>
                <span class="section-step">1</span>
                <h2>要完成什么</h2>
              </div>
              <p>设置 A+ 的量化口径、五年目标和逐年目标</p>
            </div>

            <el-form
              v-if="isNew"
              ref="formRef"
              :model="form"
              :rules="rules"
              label-position="top"
            >
              <div class="form-grid">
                <el-form-item label="A+ 指标名称" prop="name" class="span-2">
                  <el-input v-model="form.name" maxlength="200" show-word-limit placeholder="这项 A+ 具体要完成什么" />
                </el-form-item>
                <el-form-item label="指标性质" prop="nature">
                  <el-radio-group v-model="form.nature">
                    <el-radio value="CONSTRAINT">{{ enumLabel('IndicatorNature', 'CONSTRAINT', userStore.enums) }}</el-radio>
                    <el-radio value="DEVELOPMENT">{{ enumLabel('IndicatorNature', 'DEVELOPMENT', userStore.enums) }}</el-radio>
                  </el-radio-group>
                </el-form-item>
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
                  <el-select v-model="form.statCaliber" style="width: 100%" @change="onCaliberChange">
                    <el-option
                      v-for="option in caliberOptions"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="showUnit" label="计量单位">
                  <el-input v-model="form.unitLabel" placeholder="如：项、个、人、门" />
                </el-form-item>
                <el-form-item label="达成方向" prop="targetDirection">
                  <el-radio-group v-model="form.targetDirection">
                    <el-radio value="HIGHER_BETTER">{{ enumLabel('TargetDirection', 'HIGHER_BETTER', userStore.enums) }}</el-radio>
                    <el-radio value="LOWER_BETTER">{{ enumLabel('TargetDirection', 'LOWER_BETTER', userStore.enums) }}</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item v-if="showBaseline" label="基线值">
                  <el-input-number v-model="form.baselineValue" controls-position="right" :precision="4" />
                  <div class="field-tip">基线年的实际值，用于存量 / 增长率计算</div>
                </el-form-item>
                <el-form-item v-if="showTotalTarget" label="五年目标总量">
                  <el-input-number v-model="form.totalTarget" :min="0" controls-position="right" :precision="4" />
                </el-form-item>
                <el-form-item label="校级项目负责人">
                  <el-select v-model="form.ownerId" filterable clearable placeholder="默认沿用 A 的负责人" style="width: 100%">
                    <el-option
                      v-for="user in userOptions"
                      :key="user.id"
                      :label="`${user.name}（${user.username}）`"
                      :value="user.id"
                    />
                  </el-select>
                  <div class="field-tip">默认沿用 A 的负责人，也可以另行指定</div>
                </el-form-item>
              </div>

              <el-form-item v-if="form.statCaliber === 'BOOLEAN'" label="计划完成年份" prop="planYear">
                <el-radio-group v-model="form.planYear" class="boolean-years">
                  <el-radio v-for="year in years" :key="year" :value="year">{{ year }} 年</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-else :label="isPercent ? '各年度目标（%）' : '各年度目标'">
                <div class="target-grid">
                  <div v-for="year in years" :key="year" class="target-field">
                    <label>{{ year }} 年</label>
                    <el-input-number
                      v-if="isPercent"
                      :model-value="percentToDisplay(form.targets[year] as number | null)"
                      :min="0"
                      :max="999.9"
                      :precision="1"
                      controls-position="right"
                      @update:model-value="(value: number | undefined) => setPercentCell(form.targets, year, value)"
                    />
                    <el-input-number
                      v-else
                      :model-value="numberCellValue(form.targets, year)"
                      :min="0"
                      :precision="form.statCaliber === 'INCREMENT' ? 0 : 4"
                      controls-position="right"
                      @update:model-value="(value: number | undefined) => setNumberCell(form.targets, year, value)"
                    />
                  </div>
                </div>
                <div v-if="form.statCaliber === 'INCREMENT'" class="year-sum" :class="yearSumDiff === 0 ? 'is-ok' : 'is-warning'">
                  各年度之和 {{ formatTargetValue(yearTargetSum, form.statCaliber) }}，五年总量
                  {{ formatTargetValue(form.totalTarget, form.statCaliber) }}
                  <template v-if="yearSumDiff !== null">（差值 {{ formatDiff(yearSumDiff) }}）</template>
                  <div class="field-tip">增量累计型要求各年度目标之和等于五年总量</div>
                </div>
              </el-form-item>

              <el-collapse v-model="advancedActive" class="advanced-collapse">
                <el-collapse-item title="高级说明与材料要求（可选）" name="advanced">
                  <el-form-item label="填写说明">
                    <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" show-word-limit />
                  </el-form-item>
                  <el-form-item label="佐证材料要求">
                    <el-input
                      v-model="form.evidenceRequirement"
                      type="textarea"
                      :rows="2"
                      maxlength="500"
                      show-word-limit
                      placeholder="如：获奖文件、证书扫描件"
                    />
                  </el-form-item>
                </el-collapse-item>
              </el-collapse>
            </el-form>

            <template v-else-if="currentAPlus">
              <el-alert
                type="info"
                :closable="false"
                class="readonly-alert"
                title="正在继续已有 A+ 的分工；本次只修改学院分工，A+ 定义和年度目标保持只读"
              />
              <el-descriptions :column="descriptionColumns" border class="readonly-descriptions">
                <el-descriptions-item label="A+ 指标" :span="descriptionColumns > 1 ? 2 : 1">
                  <span class="mono">{{ currentAPlus.code }}</span> {{ currentAPlus.name }}
                </el-descriptions-item>
                <el-descriptions-item label="指标性质">
                  {{ enumLabel('IndicatorNature', currentAPlus.nature, userStore.enums) }}
                </el-descriptions-item>
                <el-descriptions-item label="计量类型">
                  {{ currentAPlus.measureType ? enumLabel('MeasureType', currentAPlus.measureType, userStore.enums) : '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="统计口径">
                  {{ currentAPlus.statCaliber ? enumLabel('StatCaliber', currentAPlus.statCaliber, userStore.enums) : '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="计量单位">{{ currentAPlus.unitLabel ?? '—' }}</el-descriptions-item>
                <el-descriptions-item label="达成方向">
                  {{ currentAPlus.targetDirection ? enumLabel('TargetDirection', currentAPlus.targetDirection, userStore.enums) : '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="基线值">{{ formatTargetValue(currentAPlus.baselineValue, currentAPlus.statCaliber) }}</el-descriptions-item>
                <el-descriptions-item label="五年目标">{{ currentTotalDisplay() }}</el-descriptions-item>
                <el-descriptions-item label="校级负责人">{{ currentAPlus.owner?.name ?? parent.owner?.name ?? '—' }}</el-descriptions-item>
              </el-descriptions>
              <div class="readonly-targets">
                <div v-for="year in years" :key="year" class="readonly-target">
                  <span>{{ year }} 年</span>
                  <strong>{{ formatTargetValue(targetOfCurrent(year), currentAPlus.statCaliber) }}</strong>
                </div>
              </div>
              <el-collapse class="advanced-collapse">
                <el-collapse-item title="高级说明与材料要求" name="advanced">
                  <dl class="advanced-readonly">
                    <dt>填写说明</dt>
                    <dd>{{ currentAPlus.description ?? '—' }}</dd>
                    <dt>佐证材料要求</dt>
                    <dd>{{ currentAPlus.evidenceRequirement ?? '—' }}</dd>
                  </dl>
                </el-collapse-item>
              </el-collapse>
            </template>
          </section>

          <section class="wizard-section">
            <div class="section-heading">
              <div>
                <span class="section-step">2</span>
                <h2>各学院完成多少</h2>
              </div>
              <p>选择执行单元和学院负责人，再填写各年目标</p>
            </div>

            <div v-if="summable" class="balance-panel" :class="balanced ? 'is-balanced' : 'has-gap'">
              <div class="balance-title">
                {{ balanced ? '各年度分工已与 A+ 目标对齐' : '逐年差额如下（未调平也可以先保存草稿）' }}
              </div>
              <div class="balance-grid">
                <div
                  v-for="item in liveSummary"
                  :key="item.year"
                  class="balance-item"
                  :class="item.diff === 0 ? 'is-ok' : 'is-gap'"
                >
                  <strong>{{ item.year }} 年</strong>
                  <span>{{ allocationGapText(item) }}</span>
                </div>
              </div>
            </div>
            <el-alert
              v-else
              type="info"
              :closable="false"
              class="caliber-alert"
              :title="isBoolean
                ? '是否型指标不做目标求和校验，每行选择计划完成年份'
                : '占比 / 直填百分比型不做目标求和校验，不把各学院百分比直接平均'"
            />

            <div v-if="missingOwners.length > 0" class="owner-tip">
              {{ missingOwners.map((row) => row.unitCode).join('、') }} 已填写目标但尚未选择学院负责人；可以先保存草稿，正式交给学院前需补齐
            </div>

            <div class="allocation-list">
              <div
                v-for="(row, index) in rows"
                :key="row.unitId"
                class="allocation-row"
                :class="{ 'is-historical': row.isHistorical, 'missing-owner': rowHasTarget(row.targets) && !row.ownerId }"
              >
                <div class="allocation-row-head">
                  <div class="unit-name">
                    <span class="mono">{{ row.unitCode }}</span> {{ row.unitName }}
                    <el-tag v-if="row.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
                  </div>
                  <div class="row-owner">
                    <label>学院负责人</label>
                    <el-select
                      v-if="!row.isHistorical"
                      v-model="row.ownerId"
                      filterable
                      clearable
                      placeholder="请选择负责人"
                      :class="{ 'owner-missing': rowHasTarget(row.targets) && !row.ownerId }"
                    >
                      <el-option
                        v-for="user in userOptions"
                        :key="user.id"
                        :label="`${user.name}（${user.username}）`"
                        :value="user.id"
                      />
                    </el-select>
                    <span v-else>{{ ownerName(row.ownerId) }}</span>
                  </div>
                  <el-button v-if="!row.isHistorical" link type="danger" @click="removeRow(index)">移除</el-button>
                </div>

                <div class="row-target-grid">
                  <div v-for="year in years" :key="year" class="row-target-field">
                    <label>{{ year }} 年</label>
                    <span v-if="row.isHistorical" class="readonly-value">
                      {{ formatTargetValue(row.targets[year] ?? null, caliber) }}
                    </span>
                    <el-checkbox
                      v-else-if="isBoolean"
                      :model-value="row.targets[year] === true"
                      @update:model-value="(checked: string | number | boolean) => toggleBooleanCell(row, year, checked === true)"
                    />
                    <el-input-number
                      v-else-if="isPercent"
                      :model-value="percentToDisplay(row.targets[year] as number | null)"
                      :precision="1"
                      :min="0"
                      :max="999.9"
                      controls-position="right"
                      @update:model-value="(value: number | undefined) => setPercentCell(row.targets, year, value)"
                    />
                    <el-input-number
                      v-else
                      :model-value="numberCellValue(row.targets, year)"
                      :precision="caliber === 'INCREMENT' ? 0 : 4"
                      :min="0"
                      controls-position="right"
                      @update:model-value="(value: number | undefined) => setNumberCell(row.targets, year, value)"
                    />
                  </div>
                  <div class="row-target-field row-total">
                    <label>五年合计</label>
                    <strong>{{ rowTotalDisplay(row) }}</strong>
                  </div>
                </div>
                <div v-if="!row.isHistorical" class="row-tools">
                  <el-button link type="primary" @click="copyFromPrevYear(row)">顺沿上年</el-button>
                  <el-button link type="primary" @click="clearRow(row)">清空本行</el-button>
                </div>
              </div>
              <el-empty
                v-if="rows.length === 0"
                :image-size="70"
                description="还没有学院分工，可在下方添加执行单元；也可以只保存 A+"
              />
            </div>

            <div class="add-row-bar">
              <el-select
                v-model="addRowUnitId"
                filterable
                placeholder="选择可承接新 B 的执行单元"
                :disabled="addableUnits.length === 0"
              >
                <el-option
                  v-for="unit in addableUnits"
                  :key="unit.id"
                  :label="`${unit.code} ${unit.name}`"
                  :value="unit.id"
                />
              </el-select>
              <el-button :disabled="!addRowUnitId" @click="addRow">添加学院</el-button>
            </div>
            <div class="field-tip">仅列出已启用且允许承接新 B 的执行单元</div>
          </section>
        </template>
      </template>
    </div>

    <template #footer>
      <div class="wizard-footer">
        <span>保存后仍可继续修改；不会自动定稿、正式交给学院或发送通知</span>
        <div class="footer-actions">
          <el-button @click="requestClose">取消</el-button>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="loading || loadFailed || (!isNew && !currentAPlus)"
            @click="submit"
          >
            保存分工
          </el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.wizard-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.parent-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--el-color-primary-light-9);
  border-radius: var(--radius-card);
}

.context-label,
.context-owner {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.context-title {
  margin-top: 2px;
  font-weight: 600;
}

.mono {
  font-family: var(--app-font-mono);
}

.wizard-section {
  min-width: 0;
  padding: var(--space-5);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--radius-card);
}

.selection-section {
  padding-top: var(--space-3);
  padding-bottom: 0;
  border: 0;
}

.section-heading {
  margin-bottom: var(--space-4);
}

.section-heading > div {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.section-heading h2 {
  margin: 0;
  font-size: 16px;
  line-height: 24px;
}

.section-heading p {
  margin: 2px 0 0 32px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.section-step {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 50%;
  font-size: 13px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  column-gap: var(--space-4);
}

.span-2 {
  grid-column: span 2;
}

.field-tip {
  font-size: 12px;
  line-height: 20px;
  color: var(--el-text-color-secondary);
}

.target-grid,
.row-target-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: var(--space-3);
  width: 100%;
}

.target-field label,
.row-target-field label,
.row-owner label {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
  line-height: 20px;
  color: var(--el-text-color-secondary);
}

.target-field :deep(.el-input-number),
.row-target-field :deep(.el-input-number) {
  width: 100%;
}

.boolean-years {
  flex-wrap: wrap;
}

.year-sum {
  margin-top: var(--space-3);
  font-variant-numeric: tabular-nums;
}

.year-sum.is-ok {
  color: var(--el-color-success);
}

.year-sum.is-warning {
  color: var(--el-color-warning);
}

.year-sum .field-tip {
  color: var(--el-text-color-secondary);
}

.advanced-collapse {
  margin-top: var(--space-2);
}

.readonly-alert,
.caliber-alert {
  margin-bottom: var(--space-4);
}

.readonly-descriptions {
  margin-bottom: var(--space-4);
}

.readonly-targets {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: var(--space-2);
}

.readonly-target {
  padding: var(--space-2) var(--space-3);
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
}

.readonly-target span {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.readonly-target strong {
  font-variant-numeric: tabular-nums;
}

.advanced-readonly {
  margin: 0;
}

.advanced-readonly dt {
  margin-top: var(--space-3);
  font-weight: 600;
}

.advanced-readonly dd {
  margin: var(--space-1) 0 0;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}

.balance-panel {
  margin-bottom: var(--space-4);
  padding: var(--space-3);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--radius-card);
}

.balance-panel.is-balanced {
  background: var(--el-color-success-light-9);
  border-color: var(--el-color-success-light-8);
}

.balance-panel.has-gap {
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-8);
}

.balance-title {
  margin-bottom: var(--space-2);
  font-weight: 600;
}

.balance-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(270px, 1fr));
  gap: var(--space-2);
}

.balance-item {
  display: flex;
  gap: var(--space-2);
  align-items: baseline;
  font-size: 12px;
  line-height: 20px;
}

.balance-item strong {
  flex-shrink: 0;
}

.balance-item.is-ok {
  color: var(--el-color-success);
}

.balance-item.is-gap {
  color: var(--el-color-danger);
}

.owner-tip {
  margin-bottom: var(--space-3);
  color: var(--el-color-warning);
  font-size: 12px;
}

.allocation-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.allocation-row {
  position: relative;
  min-width: 0;
  padding: var(--space-4);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--radius-card);
}

.allocation-row.missing-owner {
  border-left: 3px solid var(--el-color-warning);
}

.allocation-row.is-historical {
  background: var(--el-fill-color-light);
}

.allocation-row-head {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(220px, 300px) auto;
  gap: var(--space-4);
  align-items: end;
  margin-bottom: var(--space-3);
}

.unit-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-1);
  min-height: 32px;
  font-weight: 600;
}

.row-owner :deep(.el-select) {
  width: 100%;
}

.owner-missing :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.row-target-field {
  min-width: 0;
}

.row-total {
  padding: 0 var(--space-3);
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
}

.row-total strong,
.readonly-value {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  font-variant-numeric: tabular-nums;
}

.row-tools {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-1);
}

.add-row-bar {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

.add-row-bar :deep(.el-select) {
  width: min(360px, 100%);
}

.wizard-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.wizard-footer > span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.footer-actions {
  display: flex;
  flex-shrink: 0;
  gap: var(--space-2);
}

:deep(.el-drawer__body) {
  background: var(--el-bg-color-page);
}

:deep(.el-drawer__footer) {
  border-top: 1px solid var(--el-border-color-light);
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .allocation-row-head {
    grid-template-columns: minmax(180px, 1fr) minmax(200px, 260px) auto;
  }

  .balance-item {
    display: block;
  }

  .balance-item strong {
    display: block;
  }
}

@media (max-width: 600px) {
  .parent-context,
  .wizard-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .wizard-section {
    padding: var(--space-4) var(--space-3);
  }

  .selection-section {
    padding-right: 0;
    padding-left: 0;
  }

  .form-grid,
  .allocation-row-head {
    grid-template-columns: minmax(0, 1fr);
  }

  .span-2 {
    grid-column: span 1;
  }

  .allocation-row-head {
    gap: var(--space-2);
  }

  .allocation-row-head > .el-button {
    justify-self: stretch;
    width: 100%;
    margin: 0;
  }

  .target-grid,
  .row-target-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .balance-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .add-row-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .add-row-bar :deep(.el-select) {
    width: 100%;
  }

  .add-row-bar .el-button,
  .row-tools .el-button {
    width: 100%;
    margin: 0;
  }

  .row-tools {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-2);
  }

  .wizard-body {
    overflow-wrap: anywhere;
  }

  .wizard-body :deep(.el-button),
  .wizard-body :deep(.el-input-number),
  .wizard-body :deep(.el-radio),
  .wizard-body :deep(.el-checkbox) {
    min-height: 44px;
  }

  .wizard-body :deep(.el-radio),
  .wizard-body :deep(.el-checkbox) {
    align-items: center;
  }

  .readonly-descriptions {
    overflow-wrap: anywhere;
  }

  .footer-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }

  .footer-actions .el-button {
    width: 100%;
    min-height: 44px;
    margin: 0;
  }

  :deep(.el-drawer__body) {
    padding: var(--space-4) var(--space-3);
  }

  :deep(.el-drawer__footer) {
    padding: var(--space-3) var(--space-4);
    padding-bottom: calc(var(--space-3) + env(safe-area-inset-bottom));
  }
}
</style>
