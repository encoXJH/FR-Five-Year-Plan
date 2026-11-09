<script setup lang="ts">
import { computed } from 'vue'
import { computeRatio } from '@/utils/submission'
import { formatPercent } from '@/utils/format'
import type { StatCaliber } from '@/types/enums'
import type { MetricValue } from '@/types/submission'

const props = withDefaults(
  defineProps<{
    caliber: StatCaliber
    modelValue: MetricValue
    disabled?: boolean
    unitLabel?: string | null
  }>(),
  { disabled: false, unitLabel: null },
)

const emit = defineEmits<{
  'update:modelValue': [value: MetricValue]
}>()

function toNumber(value: string | number | null | undefined): number | null {
  if (value === '' || value === null || value === undefined) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.round(parsed * 10000) / 10000 : null
}

function patch(part: Partial<MetricValue>): void {
  emit('update:modelValue', { ...props.modelValue, ...part })
}

const amount = computed({
  get: () => props.modelValue.amount ?? '',
  set: (value: string) => patch({ amount: toNumber(value) }),
})
const total = computed({
  get: () => props.modelValue.total ?? '',
  set: (value: string) => patch({ total: toNumber(value) }),
})
const numerator = computed({
  get: () => props.modelValue.numerator ?? '',
  set: (value: string) => patch({ numerator: toNumber(value) }),
})
const denominator = computed({
  get: () => props.modelValue.denominator ?? '',
  set: (value: string) => patch({ denominator: toNumber(value) }),
})
const percentDisplay = computed({
  get: () => {
    const value = props.modelValue.percent
    return typeof value === 'number' ? Math.round(value * 1000000) / 10000 : ''
  },
  set: (value: string) => {
    const parsed = toNumber(value)
    patch({ percent: parsed === null ? null : Math.round(parsed * 10000) / 1000000 })
  },
})
const achieved = computed({
  get: () => props.modelValue.achieved === true,
  set: (value: boolean) => patch({ achieved: value }),
})

const ratio = computed(() => computeRatio(props.modelValue.numerator, props.modelValue.denominator))
</script>

<template>
  <div class="metric-input">
    <template v-if="caliber === 'INCREMENT'">
      <div class="field-label">本次新增数量<span class="required">*</span></div>
      <van-field
        v-model="amount"
        type="number"
        inputmode="decimal"
        :disabled="disabled"
        placeholder="请填写本次新增数量"
        :right-icon="unitLabel || undefined"
      />
      <p class="field-hint">年度完成值为当年审核通过数量的累计。</p>
    </template>

    <template v-else-if="caliber === 'STOCK'">
      <div class="field-label">当前总量<span class="required">*</span></div>
      <van-field
        v-model="total"
        type="number"
        inputmode="decimal"
        :disabled="disabled"
        placeholder="请填写截至当前的累计总量"
        :right-icon="unitLabel || undefined"
      />
      <p class="field-hint">年度完成值取当年最后一次审核通过的存量，增量自动计算。</p>
    </template>

    <template v-else-if="caliber === 'GROWTH'">
      <div class="field-label">当前总量<span class="required">*</span></div>
      <van-field
        v-model="total"
        type="number"
        inputmode="decimal"
        :disabled="disabled"
        placeholder="请填写当前累计总量（不需填写增长率）"
        :right-icon="unitLabel || undefined"
      />
      <p class="field-hint">年度增长率由系统按与上一年总量（首年为基线值）计算。</p>
    </template>

    <template v-else-if="caliber === 'RATIO'">
      <div class="ratio-fields">
        <div>
          <div class="field-label">分子<span class="required">*</span></div>
          <van-field
            v-model="numerator"
            type="number"
            inputmode="decimal"
            :disabled="disabled"
            placeholder="分子"
          />
        </div>
        <div>
          <div class="field-label">分母<span class="required">*</span></div>
          <van-field
            v-model="denominator"
            type="number"
            inputmode="decimal"
            :disabled="disabled"
            placeholder="分母"
          />
        </div>
      </div>
      <p class="field-hint">
        实时占比：<strong>{{ ratio === null ? '—' : formatPercent(ratio) }}</strong>；汇总按分子合计 ÷ 分母合计计算。
      </p>
    </template>

    <template v-else-if="caliber === 'DIRECT_PERCENT'">
      <div class="field-label">直填百分比<span class="required">*</span></div>
      <van-field
        v-model="percentDisplay"
        type="number"
        inputmode="decimal"
        :disabled="disabled"
        placeholder="请输入百分比数值，如 15.5"
        right-icon="%"
      />
      <p class="field-hint">按百分数填写（例如 15% 填 15）。必须填写来源说明并上传佐证材料。</p>
    </template>

    <template v-else-if="caliber === 'BOOLEAN'">
      <div class="boolean-row">
        <div>
          <div class="field-label">是否已完成</div>
          <p class="field-hint">计划完成年份完成后请开启。</p>
        </div>
        <van-switch v-model="achieved" :disabled="disabled" size="22px" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.metric-input {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field-label {
  margin: 6px 0 2px;
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
}
.required {
  margin-left: 2px;
  color: var(--danger);
}
.metric-input :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.field-hint {
  margin: 2px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 17px;
}
.ratio-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.boolean-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 52px;
  padding: 4px 0;
}
</style>
