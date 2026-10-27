<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createIndicator, updateIndicator } from '@/api/indicator'
import { getUserOptions } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { CALIBER_BY_MEASURE } from '@/utils/indicator'
import type {
  CreateIndicatorRequest,
  Indicator,
  UpdateIndicatorRequest,
  YearTarget,
} from '@/types/indicator'
import type { MeasureType, StatCaliber } from '@/types/enums'
import type { UserOption } from '@/types/user'

const props = defineProps<{
  modelValue: boolean
  planId: string
  indicator: Indicator | null
  presetParentId: string | null
  parents: { id: string; code: string; name: string }[]
  years: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const userStore = useUserStore()
const isEdit = computed(() => props.indicator !== null)
const isAPlus = computed(() =>
  isEdit.value ? props.indicator?.level === 'A_PLUS' : props.presetParentId !== null,
)
const title = computed(() => {
  if (isEdit.value) return `编辑指标 ${props.indicator?.code ?? ''}`
  return isAPlus.value ? '新增 A+ 类指标' : '新增 A 类指标'
})

interface FormState {
  parentId: string | null
  name: string
  nature: 'CONSTRAINT' | 'DEVELOPMENT'
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
}

const formRef = ref<FormInstance>()
const saving = ref(false)
const userOptions = ref<UserOption[]>([])
const form = reactive<FormState>({
  parentId: null,
  name: '',
  nature: 'CONSTRAINT',
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
})

const quantitative = computed(() => isAPlus.value || form.isQuantifiable)
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

const rules: FormRules = {
  parentId: [{ required: true, message: '请选择所属 A 类指标', trigger: 'change' }],
  name: [{ required: true, message: '请填写指标名称', trigger: 'blur' }],
  measureType: [{ required: true, message: '请选择计量类型', trigger: 'change' }],
  statCaliber: [{ required: true, message: '请选择统计口径', trigger: 'change' }],
  planYear: [{ required: true, message: '请选择计划完成年份', trigger: 'change' }],
}

function resetForm(): void {
  const source = props.indicator
  form.parentId = source?.parentId ?? props.presetParentId
  form.name = source?.name ?? ''
  form.nature = source?.nature ?? 'CONSTRAINT'
  form.isQuantifiable = isAPlus.value || (source?.isQuantifiable ?? true)
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
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    resetForm()
    formRef.value?.clearValidate()
    if (userOptions.value.length === 0) {
      getUserOptions()
        .then((list) => {
          userOptions.value = list
        })
        .catch(() => undefined)
    }
  },
)

function onMeasureTypeChange(): void {
  const allowed = CALIBER_BY_MEASURE[form.measureType]
  if (!allowed.includes(form.statCaliber)) form.statCaliber = allowed[0]
}

function buildYearTargets(): YearTarget[] | undefined {
  if (!quantitative.value || form.statCaliber !== 'BOOLEAN') return undefined
  return props.years.map((year) => ({ year, target: year === form.planYear ? true : null }))
}

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
    } as const
  }
  return {
    measureType: form.measureType,
    statCaliber: form.statCaliber,
    unitLabel: showUnit.value ? form.unitLabel || null : null,
    targetDirection: form.targetDirection,
    rollupMode: isAPlus.value ? null : form.rollupMode,
    baselineValue: showBaseline.value ? form.baselineValue : null,
    evidenceRequirement: form.evidenceRequirement || null,
  }
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  await formRef.value.validate()
  saving.value = true
  try {
    const base = {
      name: form.name.trim(),
      nature: form.nature,
      description: form.description.trim() || null,
      ownerId: form.ownerId,
      ...quantitativePayload(),
    }
    if (isEdit.value && props.indicator) {
      const payload: UpdateIndicatorRequest = {
        ...base,
        version: props.indicator.version,
      }
      if (!isAPlus.value) payload.isQuantifiable = form.isQuantifiable
      await updateIndicator(props.indicator.id, payload)
      ElMessage.success('指标已保存')
    } else {
      const payload: CreateIndicatorRequest = {
        ...base,
        level: isAPlus.value ? 'A_PLUS' : 'A',
        totalTarget: quantitative.value && showTotalTarget.value ? form.totalTarget : null,
        yearTargets: buildYearTargets(),
      }
      if (isAPlus.value) {
        payload.parentId = form.parentId ?? undefined
      } else {
        payload.isQuantifiable = form.isQuantifiable
      }
      await createIndicator(props.planId, payload)
      ElMessage.success('指标已创建')
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="520px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item v-if="isAPlus" label="所属 A 类" prop="parentId">
        <el-select v-model="form.parentId" placeholder="请选择上级 A 类指标" :disabled="isEdit" style="width: 100%">
          <el-option
            v-for="parent in parents"
            :key="parent.id"
            :label="`${parent.code} ${parent.name}`"
            :value="parent.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="指标名称" prop="name">
        <el-input v-model="form.name" maxlength="200" show-word-limit placeholder="关键产出指标名称" />
      </el-form-item>

      <el-form-item label="指标性质" prop="nature">
        <el-radio-group v-model="form.nature">
          <el-radio value="CONSTRAINT">{{ enumLabel('IndicatorNature', 'CONSTRAINT', userStore.enums) }}</el-radio>
          <el-radio value="DEVELOPMENT">{{ enumLabel('IndicatorNature', 'DEVELOPMENT', userStore.enums) }}</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="!isAPlus" label="是否可量化">
        <el-switch v-model="form.isQuantifiable" />
        <div class="field-tip">不可量化 A 仅维护基础信息，不生成 A+ / B，也不进入填报、审核、核算和完成率统计</div>
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
            <el-option v-for="option in caliberOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <div class="field-tip">计量类型与统计口径必须匹配，否则服务端返回 40007</div>
        </el-form-item>

        <el-form-item v-if="showUnit" label="计量单位">
          <el-input v-model="form.unitLabel" placeholder="如：项、个、人、门" style="width: 160px" />
        </el-form-item>

        <el-form-item label="达成方向" prop="targetDirection">
          <el-radio-group v-model="form.targetDirection">
            <el-radio value="HIGHER_BETTER">{{ enumLabel('TargetDirection', 'HIGHER_BETTER', userStore.enums) }}</el-radio>
            <el-radio value="LOWER_BETTER">{{ enumLabel('TargetDirection', 'LOWER_BETTER', userStore.enums) }}</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="!isAPlus" label="汇总方式" prop="rollupMode">
          <el-radio-group v-model="form.rollupMode">
            <el-radio value="AUTO_SUM">{{ enumLabel('RollupMode', 'AUTO_SUM', userStore.enums) }}</el-radio>
            <el-radio value="MANUAL">{{ enumLabel('RollupMode', 'MANUAL', userStore.enums) }}</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="showBaseline" label="基线值">
          <el-input-number v-model="form.baselineValue" controls-position="right" :precision="4" />
          <div class="field-tip">基线年的实际值，用于存量 / 增长率计算</div>
        </el-form-item>

        <el-form-item v-if="showTotalTarget && !isEdit" label="五年总量">
          <el-input-number v-model="form.totalTarget" :min="0" controls-position="right" :precision="4" />
          <div class="field-tip">各年度目标可在保存后到「指标详情 · 年度目标」中设置</div>
        </el-form-item>

        <el-form-item v-if="form.statCaliber === 'BOOLEAN'" label="计划完成年" prop="planYear">
          <el-radio-group v-model="form.planYear">
            <el-radio v-for="year in years" :key="year" :value="year">{{ year }} 年</el-radio>
          </el-radio-group>
        </el-form-item>
      </template>

      <el-form-item label="项目负责人">
        <el-select v-model="form.ownerId" placeholder="请选择校级项目负责人" filterable clearable style="width: 100%">
          <el-option
            v-for="user in userOptions"
            :key="user.id"
            :label="`${user.name}（${user.username}）`"
            :value="user.id"
          />
        </el-select>
        <div class="field-tip">A 类留空默认本人；A+ 留空沿用上级 A 的负责人</div>
      </el-form-item>

      <el-form-item label="填写说明">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" show-word-limit />
      </el-form-item>

      <el-form-item v-if="quantitative" label="材料要求">
        <el-input
          v-model="form.evidenceRequirement"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="佐证材料要求，如：获奖文件、证书扫描件"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">
        {{ isEdit ? '保存修改' : '创建指标' }}
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.field-tip {
  font-size: 12px;
  line-height: 20px;
  color: var(--el-text-color-secondary);
}
</style>
