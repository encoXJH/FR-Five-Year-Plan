<script setup lang="ts">
import { computed } from 'vue'
import type { AnnualTarget, Numeric, ValueKind } from '@/types/api'
import TargetValueEditor from './TargetValueEditor.vue'
const props = withDefaults(defineProps<{
  modelValue: AnnualTarget[]
  startYear: number
  endYear: number
  valueKind?: ValueKind
  unit?: string
  disabled?: boolean
  fieldSet?: 'indicator' | 'task'
  showSchool?: boolean
  showConfirm?: boolean
}>(), { valueKind: 'DECIMAL', fieldSet: 'indicator' })
const emit = defineEmits<{ 'update:modelValue': [value: AnnualTarget[]] }>()
const years = computed(() => Array.from({ length: Math.max(0, props.endYear - props.startYear + 1) }, (_, index) => props.startYear + index))
const rows = computed(() => years.value.map((year) => {
  const existing = props.modelValue.find((item) => item.target_year === year)
  return existing || { target_year: year, target_version_no: 1, confirm_status: 'PENDING' }
}))
function field(name: 'value' | 'raw' | 'text' | 'bool'): keyof AnnualTarget {
  if (props.fieldSet === 'task') return `current_target_${name === 'bool' ? 'bool' : name}` as keyof AnnualTarget
  return `professional_target_${name}` as keyof AnnualTarget
}
function value(row: AnnualTarget) { return row[field('value')] as Numeric | null | undefined }
function raw(row: AnnualTarget) { return row[field('raw')] as string | null | undefined }
function text(row: AnnualTarget) { return row[field('text')] as string | null | undefined }
function bool(row: AnnualTarget) { return row[field('bool')] as boolean | number | null | undefined }
function patch(year: number, changes: Partial<AnnualTarget>) {
  const next = rows.value.map((row) => row.target_year === year ? { ...row, ...changes } : { ...row })
  emit('update:modelValue', next)
}
</script>
<template>
  <div class="target-grid">
    <table>
      <thead><tr><th style="width:65px">年度</th><th v-for="year in years" :key="year">{{ year }} 年</th><th v-if="showSchool" style="width:150px">学校整体目标／分工</th></tr></thead>
      <tbody>
        <tr>
          <th>{{ fieldSet === 'task' ? '学院目标' : '专业群目标' }}</th>
          <td v-for="row in rows" :key="row.target_year">
            <TargetValueEditor :value="value(row)" :raw-value="raw(row)" :text-value="text(row)" :bool-value="bool(row)" :value-kind="valueKind" :unit="unit" :disabled="disabled"
              @update:value="patch(row.target_year, { [field('value')]: $event })"
              @update:raw-value="patch(row.target_year, { [field('raw')]: $event })"
              @update:text-value="patch(row.target_year, { [field('text')]: $event })"
              @update:bool-value="patch(row.target_year, { [field('bool')]: $event })" />
          </td>
          <td v-if="showSchool" class="subtle small">按指标详情分别保存学校目标与年度分工原文</td>
        </tr>
        <tr v-if="fieldSet === 'indicator'">
          <th>学校分工原文</th>
          <td v-for="row in rows" :key="`division-${row.target_year}`"><el-input :model-value="row.school_division_raw || ''" type="textarea" :rows="2" resize="vertical" :disabled="disabled" placeholder="保留原文；空白不转为 0" @update:model-value="patch(row.target_year, { school_division_raw: $event || null })" /></td>
          <td v-if="showSchool" />
        </tr>
      </tbody>
    </table>
    <div v-if="showConfirm" class="confirm-row"><span>确认状态</span><el-tag v-for="row in rows" :key="`confirm-${row.target_year}`" :type="row.confirm_status === 'CONFIRMED' ? 'success' : 'info'" size="small">{{ row.target_year }} · {{ row.confirm_status === 'CONFIRMED' ? '已确认' : '待确认' }}</el-tag></div>
  </div>
</template>
<style scoped>
.confirm-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 10px; color: #68768c; background: #f8f9fc; border: 1px solid #e6eaf1; border-top: 0; font-size: 12px; }
</style>
