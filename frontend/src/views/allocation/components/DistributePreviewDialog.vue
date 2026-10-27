<script setup lang="ts">
import { computed } from 'vue'
import { formatTargetValue } from '@/utils/indicator'
import type { StatCaliber } from '@/types/enums'

/** 预览行：一条将生成的 B 任务（前端按矩阵组装） */
export interface PreviewRow {
  code: string
  unitName: string
  ownerName: string | null
  fiveYearTotal: number | null
  booleanPlanYear: number | null
  targets: Record<number, number | boolean | null>
}

const props = defineProps<{
  modelValue: boolean
  indicatorCode: string
  indicatorName: string
  caliber: StatCaliber
  unitLabel: string | null
  years: number[]
  rows: PreviewRow[]
  confirming: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

function totalDisplay(row: PreviewRow): string {
  if (props.caliber === 'BOOLEAN') {
    return row.booleanPlanYear === null ? '—' : `是（${row.booleanPlanYear} 年）`
  }
  return formatTargetValue(row.fiveYearTotal, props.caliber)
}

function yearDisplay(row: PreviewRow, year: number): string {
  return formatTargetValue(row.targets[year] ?? null, props.caliber)
}
</script>

<template>
  <el-dialog v-model="visible" title="下发预览" width="860px" :close-on-click-modal="false">
    <el-alert
      type="info"
      :closable="false"
      class="preview-alert"
      :title="`将下发指标 ${indicatorCode} ${indicatorName}，生成 ${rows.length} 条 B 任务；下发后任务编号、负责人不可修改，目标调整需走延期 / 转移申请。`"
    />
    <el-table :data="rows" border size="small" max-height="420">
      <el-table-column prop="code" label="任务编号" width="150" class-name="mono" />
      <el-table-column prop="unitName" label="承接单元" min-width="160" show-overflow-tooltip />
      <el-table-column label="学院负责人" width="110" align="center">
        <template #default="{ row }">{{ row.ownerName ?? '—' }}</template>
      </el-table-column>
      <el-table-column
        v-for="year in years"
        :key="year"
        :label="String(year)"
        width="76"
        align="right"
      >
        <template #default="{ row }">{{ yearDisplay(row, year) }}</template>
      </el-table-column>
      <el-table-column label="五年目标" width="110" align="right">
        <template #default="{ row }">{{ totalDisplay(row) }}</template>
      </el-table-column>
    </el-table>
    <div class="unit-note" v-if="unitLabel">单位：{{ unitLabel }}</div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="confirming" @click="emit('confirm')">确认下发</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.preview-alert {
  margin-bottom: var(--space-3);
}

.mono {
  font-family: var(--app-font-mono);
}

.unit-note {
  margin-top: var(--space-2);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
