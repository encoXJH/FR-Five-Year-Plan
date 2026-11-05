<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getStatsOverview } from '@/api/stats'
import { listYearUploads } from '@/api/submission'
import type { YearUploadItem } from '@/api/submission'
import type { StatsOverview } from '@/types/stats'
import StatusTag from '@/components/StatusTag.vue'
import { formatCount, formatDateTime, formatPercent } from '@/utils/format'
import { useAutoRefresh } from '@/utils/autoRefresh'

const props = withDefaults(defineProps<{
  planId: string
  /** 页面年度筛选；为 null（五年累计）时由后端按计划起止年份解析展示年度 */
  year: number | null
  showUploads?: boolean
}>(), {
  showUploads: true,
})

const progressYear = computed(() => props.year ?? overview.value?.year ?? new Date().getFullYear())
/** 页面选择“五年累计”时展示累计进度，不再套用后端解析出的展示年度 */
const cumulative = computed(() => props.year === null)
const loading = ref(false)
const overview = ref<StatsOverview | null>(null)
const uploads = ref<YearUploadItem[]>([])
const uploadExpanded = ref(false)

const UPLOAD_PREVIEW_SIZE = 5
const visibleUploads = computed(() => uploadExpanded.value ? uploads.value : uploads.value.slice(0, UPLOAD_PREVIEW_SIZE))
const hasMoreUploads = computed(() => uploads.value.length > UPLOAD_PREVIEW_SIZE)

const achievedCount = computed(() => overview.value?.achievedCount ?? 0)
const totalCount = computed(() => overview.value?.taskCount ?? 0)
const unfinishedCount = computed(() => Math.max(0, totalCount.value - achievedCount.value))
const annualCompletionRate = computed(() => totalCount.value > 0 ? achievedCount.value / totalCount.value : null)
const cumulativeCompletionRate = computed(() => overview.value?.cumulativeCompletionRate ?? null)
const completionRate = computed(() => cumulative.value ? cumulativeCompletionRate.value : annualCompletionRate.value)
const progressPercentage = computed(() => completionRate.value === null
  ? 0
  : Math.min(100, Number((completionRate.value * 100).toFixed(1))))

async function load(silent = false): Promise<void> {
  if (!props.planId) {
    overview.value = null
    uploads.value = []
    return
  }
  if (!silent) loading.value = true
  try {
    const stats = await getStatsOverview(props.planId, props.year ?? undefined)
    const resolvedYear = stats.year ?? progressYear.value
    const items = props.showUploads && !cumulative.value
      ? await listYearUploads(props.planId, resolvedYear)
      : []
    overview.value = stats
    uploads.value = items
  } finally {
    if (!silent) loading.value = false
  }
}

watch(() => [props.planId, props.year, props.showUploads], () => {
  uploadExpanded.value = false
  void load()
}, { immediate: true })

useAutoRefresh(() => load(true), { intervalMs: 60_000 })
</script>

<template>
  <el-card v-loading="loading" shadow="never" class="year-progress-panel">
    <template #header>
      <div class="panel-header">
        <span class="card-title">{{ cumulative ? '五年累计总进度' : `${progressYear} 年总进度` }}</span>
        <span v-if="cumulative" class="card-tip">五年累计完成率 {{ formatPercent(cumulativeCompletionRate) }}</span>
        <span v-else class="card-tip">已完成 {{ formatCount(achievedCount) }} / 共 {{ formatCount(totalCount) }} 项任务</span>
      </div>
    </template>

    <div class="progress-summary" :class="{ 'is-cumulative': cumulative }">
      <div class="progress-main">
        <el-progress
          :percentage="progressPercentage"
          :stroke-width="18"
          :text-inside="false"
          :format="() => formatPercent(completionRate)"
        />
        <div class="progress-caption">
          {{ cumulative ? '五年累计指标完成进度（按指标累计完成率平均）' : '当年整体任务完成进度' }}
        </div>
      </div>
      <div v-if="!cumulative" class="progress-numbers">
        <div class="number-item">
          <div class="number-value is-good">{{ formatCount(achievedCount) }}</div>
          <div class="number-label">已完成任务</div>
        </div>
        <div class="number-item">
          <div class="number-value" :class="{ 'is-bad': unfinishedCount > 0 }">{{ formatCount(unfinishedCount) }}</div>
          <div class="number-label">未完成任务</div>
        </div>
      </div>
    </div>

    <div v-if="showUploads && !cumulative" class="upload-section">
      <div class="upload-title">当年已上传指标清单（{{ formatCount(uploads.length) }}）</div>
      <el-table v-if="uploads.length > 0" :data="visibleUploads" border size="small" max-height="360">
        <el-table-column label="指标名称" min-width="240">
          <template #default="{ row }">
            <span class="indicator-code">{{ row.indicatorCode }}</span>
            {{ row.indicatorName ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column label="所属学院" min-width="140">
          <template #default="{ row }">{{ row.unitName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="上传人" width="110">
          <template #default="{ row }">{{ row.submittedByName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="审核状态" width="110" align="center">
          <template #default="{ row }"><StatusTag kind="SubmissionStatus" :value="row.status" /></template>
        </el-table-column>
      </el-table>
      <el-empty v-else :image-size="60" description="当年暂无已上传的指标填报记录" />
      <div v-if="hasMoreUploads" class="upload-footer">
        <el-button link type="primary" @click="uploadExpanded = !uploadExpanded">
          {{ uploadExpanded ? '收起' : `展开全部 ${formatCount(uploads.length)} 条` }}
        </el-button>
        <span v-if="!uploadExpanded" class="upload-hint">仅展示最近 {{ UPLOAD_PREVIEW_SIZE }} 条</span>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.year-progress-panel { margin-bottom: 16px; }
.panel-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-title { font-weight: 600; }
.card-tip { color: var(--el-text-color-secondary); font-size: 12px; }
.progress-summary { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 24px; align-items: center; }
.progress-summary.is-cumulative { grid-template-columns: minmax(0, 1fr); }
.progress-main :deep(.el-progress__text) { min-width: 56px; font-size: 15px; font-weight: 600; color: var(--el-color-primary); }
.progress-caption { margin-top: 6px; color: var(--el-text-color-secondary); font-size: 12px; }
.progress-numbers { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.number-item { padding: 10px 12px; background: var(--el-fill-color-light); border-radius: 6px; text-align: center; }
.number-value { font-size: 26px; font-weight: 700; line-height: 32px; font-variant-numeric: tabular-nums; }
.number-value.is-good { color: var(--el-color-success); }
.number-value.is-bad { color: var(--el-color-danger); }
.number-label { margin-top: 2px; color: var(--el-text-color-secondary); font-size: 12px; }
.upload-section { margin-top: 18px; }
.upload-title { margin-bottom: 10px; font-weight: 600; }
.indicator-code { margin-right: 6px; color: var(--el-color-primary); font-weight: 600; font-variant-numeric: tabular-nums; }
.upload-footer { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.upload-footer .el-button { margin: 0; }
.upload-hint { color: var(--el-text-color-secondary); font-size: 12px; }

@media (max-width: 900px) {
  .progress-summary { grid-template-columns: minmax(0, 1fr); gap: 14px; }
}
</style>
