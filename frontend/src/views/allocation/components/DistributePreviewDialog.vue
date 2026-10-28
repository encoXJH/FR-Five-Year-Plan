<script setup lang="ts">
import { computed } from 'vue'
<<<<<<< HEAD
import ResponsiveRecordView from '@/components/ResponsiveRecordView.vue'
=======
>>>>>>> fe5ddcd2a9c737aa5a2faaeb19f4cef3e55b9e1d
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
<<<<<<< HEAD
  <el-dialog
    v-model="visible"
    title="确认正式交给学院"
    width="min(860px, calc(100vw - 32px))"
    top="5vh"
    class="distribute-preview-dialog"
    :close-on-click-modal="false"
  >
=======
  <el-dialog v-model="visible" title="下发预览" width="860px" :close-on-click-modal="false">
>>>>>>> fe5ddcd2a9c737aa5a2faaeb19f4cef3e55b9e1d
    <el-alert
      type="info"
      :closable="false"
      class="preview-alert"
<<<<<<< HEAD
      :title="`将把 ${indicatorCode} ${indicatorName} 正式交给学院，生成 ${rows.length} 条 B 任务并通知学院负责人；任务编号、负责人不可修改，目标调整需走延期 / 转移申请。`"
    />
    <ResponsiveRecordView>
      <template #table>
        <el-table :data="rows" border size="small" max-height="420">
=======
      :title="`将下发指标 ${indicatorCode} ${indicatorName}，生成 ${rows.length} 条 B 任务；下发后任务编号、负责人不可修改，目标调整需走延期 / 转移申请。`"
    />
    <el-table :data="rows" border size="small" max-height="420">
>>>>>>> fe5ddcd2a9c737aa5a2faaeb19f4cef3e55b9e1d
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
<<<<<<< HEAD
        </el-table>
      </template>
      <template #cards>
        <div class="responsive-card-list preview-card-list">
          <article v-for="row in rows" :key="row.code" class="responsive-card preview-card">
            <div class="responsive-card__header">
              <div class="responsive-card__title mono">{{ row.code }}</div>
              <el-tag type="primary" effect="plain" size="small">B 任务</el-tag>
            </div>
            <div class="preview-unit">{{ row.unitName }}</div>
            <div class="responsive-card__fields">
              <div class="responsive-card__field">
                <span class="responsive-card__field-label">学院负责人</span>
                {{ row.ownerName ?? '—' }}
              </div>
              <div class="responsive-card__field">
                <span class="responsive-card__field-label">五年目标</span>
                <strong>{{ totalDisplay(row) }}</strong>
              </div>
              <div v-if="unitLabel" class="responsive-card__field">
                <span class="responsive-card__field-label">计量单位</span>
                {{ unitLabel }}
              </div>
            </div>
            <div class="preview-year-title">各年度目标</div>
            <div class="responsive-year-grid">
              <div v-for="year in years" :key="year" class="preview-year-card">
                <span>{{ year }} 年</span>
                <strong>{{ yearDisplay(row, year) }}</strong>
              </div>
            </div>
          </article>
          <el-empty v-if="rows.length === 0" :image-size="80" description="暂无可下发的 B 任务" />
        </div>
      </template>
    </ResponsiveRecordView>
    <div class="unit-note" v-if="unitLabel">单位：{{ unitLabel }}</div>
    <template #footer>
      <div class="preview-footer responsive-safe-bottom">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="confirming" @click="emit('confirm')">确认交给学院</el-button>
      </div>
=======
    </el-table>
    <div class="unit-note" v-if="unitLabel">单位：{{ unitLabel }}</div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="confirming" @click="emit('confirm')">确认下发</el-button>
>>>>>>> fe5ddcd2a9c737aa5a2faaeb19f4cef3e55b9e1d
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
<<<<<<< HEAD

.preview-card-list,
.preview-card {
  min-width: 0;
}

.preview-unit {
  margin-top: var(--space-1);
  overflow-wrap: anywhere;
  color: var(--el-text-color-regular);
}

.preview-year-title {
  margin-bottom: var(--space-2);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.preview-year-card {
  min-width: 0;
  padding: var(--space-2) var(--space-3);
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
  font-variant-numeric: tabular-nums;
}

.preview-year-card > span,
.preview-year-card > strong {
  display: block;
  overflow-wrap: anywhere;
}

.preview-year-card > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.preview-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}

:deep(.distribute-preview-dialog) {
  display: flex;
  max-height: calc(100vh - 32px);
  max-height: calc(100dvh - 32px);
  flex-direction: column;
  overflow: hidden;
}

:deep(.distribute-preview-dialog .el-dialog__header),
:deep(.distribute-preview-dialog .el-dialog__footer) {
  flex-shrink: 0;
}

:deep(.distribute-preview-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

@media (max-width: 768px) {
  .preview-footer {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }

  .preview-footer .el-button {
    width: 100%;
    min-height: 44px;
    margin: 0;
  }

  :deep(.distribute-preview-dialog) {
    top: 16px;
    max-height: calc(100vh - 32px);
    max-height: calc(100dvh - 32px);
    margin-bottom: 16px;
  }

  :deep(.distribute-preview-dialog .el-dialog__header),
  :deep(.distribute-preview-dialog .el-dialog__body),
  :deep(.distribute-preview-dialog .el-dialog__footer) {
    padding-right: var(--space-4);
    padding-left: var(--space-4);
  }

  :deep(.distribute-preview-dialog .el-dialog__body) {
    padding-top: var(--space-3);
    padding-bottom: var(--space-3);
  }
}
=======
>>>>>>> fe5ddcd2a9c737aa5a2faaeb19f4cef3e55b9e1d
</style>
