<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import BackButton from '@/components/BackButton.vue'
import { getStatsTree, getIndicatorTrend } from '@/api/stats'
import { getPlan } from '@/api/plan'
import type { IndicatorTrend, TrendYearItem } from '@/types/stats'
import type { StatCaliber } from '@/types/enums'
import { EMPTY_DISPLAY, formatCount, formatNumber, formatPercent } from '@/utils/format'
import { formatTargetValue, isPercentCaliber } from '@/utils/indicator'

const route = useRoute()
const planId = typeof route.params.planId === 'string' ? route.params.planId : ''

const loading = ref(false)
const planName = ref('')
const trends = ref<IndicatorTrend[]>([])
const indicatorTotal = ref(0)

const cumulativeTotal = computed(() => trends.value.reduce((sum, item) => {
  return sum + (typeof item.cumulativeActual === 'number' ? item.cumulativeActual : 0)
}, 0))

function actualText(value: number | null | undefined, caliber: StatCaliber | null): string {
  if (value === null || value === undefined) return EMPTY_DISPLAY
  if (caliber === 'BOOLEAN') return value >= 1 ? '是' : '否'
  if (isPercentCaliber(caliber)) return formatPercent(value)
  return formatNumber(value)
}

function barPercentage(item: TrendYearItem): number {
  if (typeof item.completionRate !== 'number') return 0
  return Math.min(100, Math.max(0, Number((item.completionRate * 100).toFixed(1))))
}

function rateText(item: TrendYearItem): string {
  return typeof item.completionRate === 'number' ? formatPercent(item.completionRate) : '0.0%'
}

function barStatus(item: TrendYearItem): 'success' | undefined {
  return typeof item.completionRate === 'number' && item.completionRate >= 1 ? 'success' : undefined
}

async function load(): Promise<void> {
  if (!planId) return
  loading.value = true
  try {
    const [plan, tree] = await Promise.all([getPlan(planId), getStatsTree(planId)])
    planName.value = plan.name
    const aNodes = tree.filter((node) => node.level === 'A')
    indicatorTotal.value = aNodes.length
    const results = await Promise.allSettled(aNodes.map((node) => getIndicatorTrend(node.id)))
    trends.value = results
      .filter((result): result is PromiseFulfilledResult<IndicatorTrend> => result.status === 'fulfilled')
      .map((result) => result.value)
      .sort((a, b) => a.code.localeCompare(b.code, 'zh-CN', { numeric: true }))
    if (trends.value.length < aNodes.length) {
      ElMessage.warning('部分指标进度加载失败，可刷新重试')
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="indicator-progress-page">
    <BackButton />
    <div class="page-header">
      <div>
        <h1 class="page-title">关键产出指标进度可视化</h1>
        <p class="page-subtitle">{{ planName }} · 五年总目标、逐年目标与完成量、累计完成情况</p>
      </div>
    </div>

    <div class="summary-cards">
      <el-card shadow="never" class="summary-card">
        <div class="summary-label">关键产出指标总数</div>
        <div class="summary-value">{{ formatCount(indicatorTotal) }}</div>
        <div class="summary-hint">个</div>
      </el-card>
      <el-card shadow="never" class="summary-card">
        <div class="summary-label">累计总已完成量</div>
        <div class="summary-value">{{ formatNumber(cumulativeTotal) }}</div>
        <div class="summary-hint">各指标五年累计完成值之和</div>
      </el-card>
    </div>

    <div v-if="!loading && trends.length === 0" class="empty-wrapper">
      <el-empty :image-size="90" description="暂无可量化的关键产出指标" />
    </div>

    <div class="indicator-grid">
      <el-card v-for="item in trends" :key="item.indicatorId" shadow="never" class="indicator-card">
        <div class="indicator-head">
          <span class="indicator-code">{{ item.code }}</span>
          <span class="indicator-name" :title="item.name">{{ item.name }}</span>
        </div>
        <div class="indicator-totals">
          <div>
            <span class="total-label">五年总目标</span>
            <strong class="total-value">{{ formatTargetValue(item.totalTarget, item.statCaliber) }}</strong>
            <span v-if="item.unitLabel" class="total-unit">{{ item.unitLabel }}</span>
          </div>
          <div>
            <span class="total-label">五年累计完成</span>
            <strong class="total-value">{{ actualText(item.cumulativeActual, item.statCaliber) }}</strong>
          </div>
          <div>
            <span class="total-label">五年完成率</span>
            <strong class="total-value rate">{{ formatPercent(item.cumulativeRate) }}</strong>
          </div>
        </div>
        <div class="year-list">
          <el-tooltip
            v-for="yearItem in item.years"
            :key="yearItem.year"
            placement="top"
            :show-after="150"
            :content="`${yearItem.year} 年目标：${formatTargetValue(yearItem.target, item.statCaliber)}；已完成：${actualText(yearItem.actualValue, item.statCaliber)}`"
          >
            <div class="year-row">
              <span class="year-label">{{ yearItem.year }}</span>
              <el-progress
                class="year-progress"
                :percentage="barPercentage(yearItem)"
                :status="barStatus(yearItem)"
                :stroke-width="16"
                :text-inside="true"
                :format="() => rateText(yearItem)"
              />
              <span class="year-values">
                {{ actualText(yearItem.actualValue, item.statCaliber) }} / {{ formatTargetValue(yearItem.target, item.statCaliber) }}
              </span>
            </div>
          </el-tooltip>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.indicator-progress-page { padding-bottom: 24px; }
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; }
.page-subtitle { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.summary-cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
.summary-card :deep(.el-card__body) { padding: 18px 20px; }
.summary-label { color: var(--el-text-color-secondary); font-size: 13px; }
.summary-value { margin-top: 6px; color: var(--el-color-primary); font-size: 30px; font-weight: 700; line-height: 38px; font-variant-numeric: tabular-nums; }
.summary-hint { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
.empty-wrapper { padding: 40px 0; }
.indicator-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.indicator-card :deep(.el-card__body) { padding: 16px 18px; }
.indicator-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 12px; }
.indicator-code { flex-shrink: 0; color: var(--el-color-primary); font-weight: 700; font-variant-numeric: tabular-nums; }
.indicator-name { font-weight: 600; line-height: 22px; }
.indicator-totals { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; padding: 10px 12px; margin-bottom: 14px; background: var(--el-color-primary-light-9); border-radius: 6px; }
.total-label { display: block; color: var(--el-text-color-secondary); font-size: 12px; }
.total-value { display: inline-block; margin-top: 3px; font-size: 18px; font-variant-numeric: tabular-nums; }
.total-value.rate { color: var(--el-color-primary); }
.total-unit { margin-left: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
.year-list { display: flex; flex-direction: column; gap: 10px; }
.year-row { display: grid; grid-template-columns: 44px minmax(0, 1fr) 132px; align-items: center; gap: 10px; }
.year-label { color: var(--el-text-color-regular); font-size: 13px; font-variant-numeric: tabular-nums; }
.year-progress { width: 100%; }
.year-progress :deep(.el-progress-bar__innerText) { font-size: 11px; }
.year-values { text-align: right; color: var(--el-text-color-regular); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }

@media (max-width: 1100px) {
  .indicator-grid { grid-template-columns: minmax(0, 1fr); }
}

@media (max-width: 768px) {
  .page-header { align-items: flex-start; flex-direction: column; }
  .summary-cards { grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .indicator-totals { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .year-row { grid-template-columns: 40px minmax(0, 1fr); }
  .year-values { grid-column: 2; text-align: left; }
}
</style>
