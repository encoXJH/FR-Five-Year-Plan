<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { showToast } from 'vant'
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

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    planId: string
    indicator: Indicator | null
    years: number[]
    directionOptions?: IndicatorDirectionOption[]
    planArchived?: boolean
  }>(),
  { directionOptions: () => [], planArchived: false },
)

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
const showTotalTarget = computed(
  () => form.statCaliber !== 'BOOLEAN' && form.statCaliber !== 'RATIO' && form.statCaliber !== 'DIRECT_PERCENT',
)
const showUnit = computed(() => form.statCaliber !== 'BOOLEAN')

const directionSelectOptions = computed(() => {
  const options = props.directionOptions.map((option) => ({
    id: option.id,
    text: option.name || option.label || option.code || option.id,
  }))
  if (form.directionId && !options.some((option) => option.id === form.directionId)) {
    options.unshift({ id: form.directionId, text: '当前建设方向' })
  }
  return options
})

function resetForm(): void {
  const source = workingIndicator.value
  form.name = source?.name ?? ''
  form.nature = source?.nature ?? 'CONSTRAINT'
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

async function initialize(): Promise<void> {
  const requestId = ++openRequestId
  detailLoading.value = false
  detailError.value = ''
  workingIndicator.value = props.indicator
  resetForm()
  loadOptions()
  if (!props.indicator) return
  detailLoading.value = true
  try {
    const detail = await getIndicator(props.indicator.id)
    if (requestId !== openRequestId) return
    workingIndicator.value = detail
    resetForm()
  } catch {
    if (requestId === openRequestId) detailError.value = '完整指标详情加载失败，请重试后再保存'
  } finally {
    if (requestId === openRequestId) detailLoading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) void initialize()
    else openRequestId += 1
  },
)

function onMeasureTypeChange(): void {
  const allowed = CALIBER_BY_MEASURE[form.measureType]
  if (!allowed.includes(form.statCaliber)) form.statCaliber = allowed[0]
}

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

function validate(): boolean {
  if (!form.name.trim()) {
    showToast('请填写指标名称')
    return false
  }
  if (quantitative.value) {
    if (!form.measureType || !form.statCaliber) {
      showToast('请选择计量类型和统计口径')
      return false
    }
    if (showUnit.value && !form.unitLabel.trim()) {
      showToast('请填写计量单位')
      return false
    }
    if (form.statCaliber === 'BOOLEAN' && form.planYear === null) {
      showToast('请选择计划完成年份')
      return false
    }
  }
  return true
}

async function submit(): Promise<void> {
  if (saving.value || detailLoading.value || detailError.value || formReadOnly.value) return
  if (!validate()) return
  saving.value = true
  try {
    const base = {
      name: form.name.trim(),
      nature: form.nature,
      description: form.description.trim() || null,
      ownerId: form.ownerId,
      ...quantitativePayload(),
    }
    if (workingIndicator.value) {
      const previousDirectionId = workingIndicator.value.directionId ?? null
      const payload: UpdateIndicatorRequest = {
        ...base,
        version: workingIndicator.value.version,
        isQuantifiable: form.isQuantifiable,
      }
      workingIndicator.value = await updateIndicator(workingIndicator.value.id, payload)
      if (isAdmin.value && form.directionId !== previousDirectionId) {
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
        ...(isAdmin.value ? { directionId: form.directionId } : {}),
      }
      workingIndicator.value = await createIndicator(props.planId, payload)
    }

    if (quantitative.value && workingIndicator.value) {
      workingIndicator.value = await updateYearTargets(workingIndicator.value.id, {
        totalTarget: showTotalTarget.value ? form.totalTarget : null,
        yearTargets: buildYearTargets() ?? [],
        version: workingIndicator.value.version,
      })
    }

    showToast(quantitative.value ? '指标与年度目标已保存' : '指标已保存')
    emit('update:modelValue', false)
    emit('saved')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

// ---------- 选择器 ----------
const picker = ref<'' | 'measure' | 'caliber' | 'owner' | 'direction'>('')
const pickerOptions = computed(() => {
  switch (picker.value) {
    case 'measure':
      return enumOptions('MeasureType', userStore.enums)
    case 'caliber':
      return caliberOptions.value
    case 'owner':
      return userOptions.value.map((item) => ({ value: item.id, label: `${item.name}（${item.username}）` }))
    case 'direction':
      return directionSelectOptions.value.map((item) => ({ value: item.id, label: item.text }))
    default:
      return []
  }
})
const pickerTitle = computed(() => {
  switch (picker.value) {
    case 'measure':
      return '计量类型'
    case 'caliber':
      return '统计口径'
    case 'owner':
      return '项目负责人'
    case 'direction':
      return '主要建设方向'
    default:
      return ''
  }
})
const measureLabel = computed(() => enumLabel('MeasureType', form.measureType, userStore.enums))
const caliberLabel = computed(() => enumLabel('StatCaliber', form.statCaliber, userStore.enums))
const ownerLabel = computed(
  () => userOptions.value.find((item) => item.id === form.ownerId)?.name ?? '',
)
const directionLabel = computed(
  () => directionSelectOptions.value.find((item) => item.id === form.directionId)?.text ?? '',
)

function applyPicker(value: string): void {
  switch (picker.value) {
    case 'measure':
      form.measureType = value as MeasureType
      onMeasureTypeChange()
      break
    case 'caliber':
      form.statCaliber = value as StatCaliber
      break
    case 'owner':
      form.ownerId = value || null
      break
    case 'direction':
      form.directionId = value || null
      break
  }
  picker.value = ''
}
</script>

<template>
  <van-popup
    :show="modelValue"
    position="bottom"
    round
    :style="{ height: '92vh' }"
    @update:show="emit('update:modelValue', $event)"
  >
    <div class="edit-sheet">
      <div class="sheet-head">
        <strong>{{ title }}</strong>
        <button type="button" class="close-btn" @click="emit('update:modelValue', false)">
          <van-icon name="cross" size="18" />
        </button>
      </div>

      <div class="sheet-body">
        <div v-if="detailLoading" class="skeleton-block">加载指标详情…</div>
        <div v-else-if="detailError" class="error-box">
          {{ detailError }}
          <van-button size="small" type="primary" plain @click="initialize">重新加载</van-button>
        </div>
        <template v-else>
          <div v-if="historical || distributed || planArchived" class="info-box">
            <template v-if="historical">该指标含历史分解数据，基础信息只读</template>
            <template v-else-if="distributed">该指标已下发，基础信息只读；目标变更请发起调整申请</template>
            <template v-else>该计划已归档，指标只读</template>
          </div>

          <div class="field">
            <label>指标名称<span class="required">*</span></label>
            <van-field v-model="form.name" :disabled="formReadOnly" maxlength="200" show-word-limit placeholder="关键产出指标名称" />
          </div>

          <div class="field">
            <label>指标性质</label>
            <div class="chip-group">
              <button
                type="button"
                class="filter-chip"
                :class="{ 'is-active': form.nature === 'CONSTRAINT' }"
                :disabled="formReadOnly"
                @click="form.nature = 'CONSTRAINT'"
              >{{ enumLabel('IndicatorNature', 'CONSTRAINT', userStore.enums) }}</button>
              <button
                type="button"
                class="filter-chip"
                :class="{ 'is-active': form.nature === 'DEVELOPMENT' }"
                :disabled="formReadOnly"
                @click="form.nature = 'DEVELOPMENT'"
              >{{ enumLabel('IndicatorNature', 'DEVELOPMENT', userStore.enums) }}</button>
            </div>
          </div>

          <div class="field">
            <label>主要建设方向</label>
            <van-field
              readonly
              is-link
              :disabled="formReadOnly || !isAdmin || directionSelectOptions.length === 0"
              :model-value="directionLabel"
              placeholder="请选择主要建设方向"
              @click="picker = 'direction'"
            />
            <p v-if="!isAdmin" class="field-tip">仅平台管理员可修改主要建设方向</p>
          </div>

          <div class="switch-field">
            <div>
              <div class="field-label">是否可量化</div>
              <p class="field-tip">不可量化 A 仅维护基础信息，不生成任务，不参与统计</p>
            </div>
            <van-switch v-model="form.isQuantifiable" :disabled="formReadOnly" size="22px" />
          </div>

          <template v-if="quantitative">
            <div class="field">
              <label>计量类型<span class="required">*</span></label>
              <van-field readonly is-link :disabled="formReadOnly" :model-value="measureLabel" @click="picker = 'measure'" />
            </div>
            <div class="field">
              <label>统计口径<span class="required">*</span></label>
              <van-field readonly is-link :disabled="formReadOnly" :model-value="caliberLabel" @click="picker = 'caliber'" />
              <p class="field-tip">计量类型与统计口径必须匹配，否则服务端返回 40007</p>
            </div>
            <div v-if="showUnit" class="field">
              <label>计量单位<span class="required">*</span></label>
              <van-field v-model="form.unitLabel" :disabled="formReadOnly" placeholder="如：项、个、人、门" />
            </div>

            <div class="field">
              <label>达成方向</label>
              <div class="chip-group">
                <button
                  type="button"
                  class="filter-chip"
                  :class="{ 'is-active': form.targetDirection === 'HIGHER_BETTER' }"
                  :disabled="formReadOnly"
                  @click="form.targetDirection = 'HIGHER_BETTER'"
                >越大越好</button>
                <button
                  type="button"
                  class="filter-chip"
                  :class="{ 'is-active': form.targetDirection === 'LOWER_BETTER' }"
                  :disabled="formReadOnly"
                  @click="form.targetDirection = 'LOWER_BETTER'"
                >越小越好</button>
              </div>
            </div>

            <div class="field">
              <label>汇总方式</label>
              <div class="chip-group">
                <button
                  type="button"
                  class="filter-chip"
                  :class="{ 'is-active': form.rollupMode === 'AUTO_SUM' }"
                  :disabled="formReadOnly"
                  @click="form.rollupMode = 'AUTO_SUM'"
                >自动汇总</button>
                <button
                  type="button"
                  class="filter-chip"
                  :class="{ 'is-active': form.rollupMode === 'MANUAL' }"
                  :disabled="formReadOnly"
                  @click="form.rollupMode = 'MANUAL'"
                >手工认定</button>
              </div>
            </div>

            <div v-if="showBaseline" class="field">
              <label>基线值</label>
              <van-field
                :model-value="form.baselineValue ?? ''"
                type="number"
                inputmode="decimal"
                :disabled="formReadOnly"
                placeholder="基线年的实际值"
                @update:model-value="(value: string) => (form.baselineValue = value === '' ? null : Number(value))"
              />
              <p class="field-tip">用于存量 / 增长率计算</p>
            </div>

            <div v-if="showTotalTarget" class="field">
              <label>五年总量</label>
              <div class="row">
                <van-field
                  :model-value="form.totalTarget ?? ''"
                  class="grow"
                  type="number"
                  inputmode="decimal"
                  :disabled="formReadOnly"
                  placeholder="五年总量"
                  @update:model-value="(value: string) => (form.totalTarget = value === '' ? null : Number(value))"
                />
                <van-button
                  v-if="form.statCaliber === 'INCREMENT'"
                  size="small"
                  :disabled="formReadOnly || form.totalTarget === null"
                  @click="distributeTotalEvenly"
                >平均分配</van-button>
              </div>
            </div>

            <div v-if="form.statCaliber === 'BOOLEAN'" class="field">
              <label>计划完成年<span class="required">*</span></label>
              <div class="chip-group">
                <button
                  v-for="year in years"
                  :key="year"
                  type="button"
                  class="filter-chip"
                  :class="{ 'is-active': form.planYear === year }"
                  :disabled="formReadOnly"
                  @click="form.planYear = year"
                >{{ year }}</button>
              </div>
            </div>

            <div v-else class="field">
              <label>各年度目标</label>
              <div class="year-grid">
                <div v-for="year in years" :key="year" class="year-input">
                  <span>{{ year }}</span>
                  <van-field
                    :model-value="targetInputs[year] ?? ''"
                    type="number"
                    inputmode="decimal"
                    :disabled="formReadOnly"
                    :placeholder="isPercentCaliber(form.statCaliber) ? '百分数' : '目标值'"
                    @update:model-value="(value: string) => (targetInputs[year] = value === '' ? null : Number(value))"
                  />
                </div>
              </div>
              <p
                v-if="form.statCaliber === 'INCREMENT'"
                class="field-tip"
                :class="sumDiff === 0 ? 'text-success' : 'text-danger'"
              >
                各年之和 {{ formatNumber(yearSum) }}，五年总量 {{ formatNumber(form.totalTarget) }}
                <template v-if="sumDiff !== null">（{{ sumDiff === 0 ? '0 ✓' : `差值 ${sumDiff > 0 ? '+' : ''}${sumDiff}` }}）</template>
                ；增量累计型要求相等（40002）
              </p>
              <p v-else-if="isPercentCaliber(form.statCaliber)" class="field-tip">百分比按百分数填写，如 15 表示 15%。</p>
            </div>
          </template>

          <div class="field">
            <label>项目负责人</label>
            <van-field
              readonly
              is-link
              :disabled="formReadOnly"
              :model-value="ownerLabel"
              placeholder="请选择建设方向负责人"
              @click="picker = 'owner'"
            />
            <p class="field-tip">留空默认由本人负责</p>
          </div>

          <div class="field">
            <label>填写说明</label>
            <van-field v-model="form.description" type="textarea" rows="3" maxlength="2000" show-word-limit :disabled="formReadOnly" />
          </div>

          <template v-if="quantitative">
            <div class="field">
              <label>材料考核要求</label>
              <van-field
                v-model="form.evidenceRequirement"
                type="textarea"
                rows="2"
                maxlength="500"
                show-word-limit
                :disabled="formReadOnly"
                placeholder="佐证材料要求，如：获奖文件、证书扫描件"
              />
            </div>
            <div class="field">
              <label>最少材料数</label>
              <van-stepper v-model="form.minFileCount" :min="0" :max="99" integer :disabled="formReadOnly" />
              <p class="field-tip">每次填报需上传的佐证材料最少份数，0 表示不强制</p>
            </div>
          </template>
        </template>
      </div>

      <div class="sheet-footer">
        <van-button block :disabled="saving" @click="emit('update:modelValue', false)">
          {{ formReadOnly ? '关闭' : '取消' }}
        </van-button>
        <van-button
          v-if="!formReadOnly"
          block
          type="primary"
          :loading="saving"
          :disabled="detailLoading || Boolean(detailError)"
          @click="submit"
        >{{ isEdit ? '保存修改' : '创建指标' }}</van-button>
      </div>
    </div>

    <van-popup :show="picker !== ''" position="bottom" round @update:show="picker = ''">
      <van-picker
        :title="pickerTitle"
        :columns="pickerOptions.map((item) => ({ text: item.label, value: item.value }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: string }> }) => applyPicker(value.selectedOptions[0]?.value ?? '')"
        @cancel="picker = ''"
      />
    </van-popup>
  </van-popup>
</template>

<style scoped>
.edit-sheet {
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
  flex-shrink: 0;
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
  padding: 12px 16px;
}
.sheet-footer {
  display: flex;
  gap: 10px;
  padding: 10px 16px calc(10px + var(--safe-bottom));
  border-top: 1px solid var(--line);
  flex-shrink: 0;
}
.info-box,
.error-box {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #eef3fc;
  color: #4a5f85;
  font-size: 12px;
  line-height: 18px;
}
.error-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fdecec;
  color: var(--danger);
}
.field {
  margin-bottom: 12px;
}
.field label,
.field-label {
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
.field-tip {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 17px;
}
.chip-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.switch-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 0;
}
.year-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.year-input {
  display: flex;
  align-items: center;
  gap: 10px;
}
.year-input > span {
  flex: 0 0 44px;
  color: var(--text-secondary);
  font-size: 12px;
}
.year-input :deep(.van-field) {
  flex: 1;
}
</style>
