<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
<<<<<<< HEAD
import BackButton from '@/components/BackButton.vue'
import YearProgressPanel from '@/views/stats/components/YearProgressPanel.vue'
import { getStatsTree, getStatsOverview } from '@/api/stats'
import { listIndicators } from '@/api/indicator'
import { getPlan } from '@/api/plan'
import type { StatsOverview, StatsTreeNode } from '@/types/stats'
import type { IndicatorTreeNode } from '@/types/indicator'
import { formatCount, formatPercent } from '@/utils/format'
=======
import { ElMessage } from 'element-plus'
import BackButton from '@/components/BackButton.vue'
import { getStatsTree, getIndicatorTrend } from '@/api/stats'
import { getPlan } from '@/api/plan'
import type { IndicatorTrend, TrendYearItem } from '@/types/stats'
import type { StatCaliber } from '@/types/enums'
import { EMPTY_DISPLAY, formatCount, formatNumber, formatPercent } from '@/utils/format'
import { formatTargetValue, isPercentCaliber } from '@/utils/indicator'
>>>>>>> 67ea59fc012446865a59fbfd6c079e57e509c4af

const route = useRoute()
const planId = typeof route.params.planId === 'string' ? route.params.planId : ''

const loading = ref(false)
const planName = ref('')
<<<<<<< HEAD
const overview = ref<StatsOverview | null>(null)
const aNodes = ref<StatsTreeNode[]>([])
const directionByIndicator = ref<Map<string, string>>(new Map())
const directionOrder = ref<Map<string, number>>(new Map())

interface DirectionGroup {
  name: string
  indicatorCount: number
  achievedCount: number
  inProgressCount: number
  cumulativeRate: number | null
  rates: number[]
}

const groups = computed<DirectionGroup[]>(() => {
  const map = new Map<string, DirectionGroup>()
  for (const node of aNodes.value) {
    const name = directionByIndicator.value.get(node.id) ?? '未归入建设方向'
    let group = map.get(name)
    if (!group) {
      group = { name, indicatorCount: 0, achievedCount: 0, inProgressCount: 0, cumulativeRate: null, rates: [] }
      map.set(name, group)
    }
    group.indicatorCount += 1
    if (typeof node.cumulativeRate === 'number') group.rates.push(node.cumulativeRate)
    if (node.achieveStatus === 'ACHIEVED' || node.achieveStatus === 'EXCEEDED') {
      group.achievedCount += 1
    } else if (node.achieveStatus === 'IN_PROGRESS' || node.achieveStatus === 'NOT_STARTED' || node.achieveStatus === 'LAGGING') {
      group.inProgressCount += 1
    }
  }
  for (const group of map.values()) {
    group.cumulativeRate = group.rates.length
      ? group.rates.reduce((sum, rate) => sum + rate, 0) / group.rates.length
      : null
  }
  return [...map.values()].sort((a, b) =>
    (directionOrder.value.get(a.name) ?? Number.MAX_SAFE_INTEGER)
    - (directionOrder.value.get(b.name) ?? Number.MAX_SAFE_INTEGER))
})

function barPercentage(rate: number | null): number {
  if (typeof rate !== 'number') return 0
  return Math.min(100, Math.max(0, Number((rate * 100).toFixed(1))))
}

function collectDirectionMap(nodes: IndicatorTreeNode[]): void {
  const byIndicator = new Map<string, string>()
  const order = new Map<string, number>()
  for (const node of nodes) {
    if (node.level !== 'A') continue
    const name = node.directionName || '未归入建设方向'
    byIndicator.set(node.id, name)
    if (!order.has(name)) order.set(name, order.size)
  }
  directionByIndicator.value = byIndicator
  directionOrder.value = order
=======
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
>>>>>>> 67ea59fc012446865a59fbfd6c079e57e509c4af
}

async function load(): Promise<void> {
  if (!planId) return
  loading.value = true
  try {
<<<<<<< HEAD
    const [plan, tree, indicatorTree, stats] = await Promise.all([
      getPlan(planId),
      getStatsTree(planId),
      listIndicators(planId),
      getStatsOverview(planId),
    ])
    planName.value = plan.name
    overview.value = stats
    collectDirectionMap(indicatorTree)
    aNodes.value = tree
      .filter((node) => node.level === 'A')
      .sort((a, b) => a.code.localeCompare(b.code, 'zh-CN', { numeric: true }))
=======
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
>>>>>>> 67ea59fc012446865a59fbfd6c079e57e509c4af
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
<<<<<<< HEAD
        <p class="page-subtitle">{{ planName }} · 仅按主要建设方向汇总展示五年完成进度</p>
      </div>
    </div>

    <div v-if="overview" class="kpi-row">
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ formatPercent(overview.cumulativeCompletionRate) }}</div>
        <div class="kpi-label">
          五年累计完成率
          <el-tooltip content="五年累计口径：各指标「累计完成 ÷ 五年总量目标」的平均；与年度选择无关" placement="top">
            <span class="kpi-tip">ⓘ</span>
          </el-tooltip>
        </div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ formatCount(overview.taskCount) }}</div>
        <div class="kpi-label">指标总数</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value is-good">{{ formatCount(overview.achievedCount) }}</div>
        <div class="kpi-label">达标</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ formatCount(overview.inProgressCount) }}</div>
        <div class="kpi-label">进行中</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value" :class="{ 'is-bad': overview.warningCount > 0 }">
          {{ formatCount(overview.warningCount) }}
        </div>
        <div class="kpi-label">滞后预警</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value" :class="{ 'is-bad': overview.notAchievedCount > 0 }">
          {{ formatCount(overview.notAchievedCount) }}
        </div>
        <div class="kpi-label">未达标</div>
      </el-card>
    </div>

    <YearProgressPanel :plan-id="planId" :year="null" :show-uploads="false" />

    <div v-if="!loading && groups.length === 0" class="empty-wrapper">
      <el-empty :image-size="90" description="暂无可量化的关键产出指标" />
    </div>

    <div class="direction-grid">
      <el-card v-for="group in groups" :key="group.name" shadow="never" class="direction-card">
        <h2 class="direction-title">{{ group.name }}</h2>
        <div class="direction-stats">
          <div class="direction-stat">
            <span class="direction-stat__label">五年完成率</span>
            <strong class="direction-stat__value is-rate">{{ formatPercent(group.cumulativeRate) }}</strong>
          </div>
          <div class="direction-stat">
            <span class="direction-stat__label">指标数量</span>
            <strong class="direction-stat__value">{{ formatCount(group.indicatorCount) }}</strong>
          </div>
          <div class="direction-stat">
            <span class="direction-stat__label">达标数量</span>
            <strong class="direction-stat__value is-good">{{ formatCount(group.achievedCount) }}</strong>
          </div>
          <div class="direction-stat">
            <span class="direction-stat__label">进行中数量</span>
            <strong class="direction-stat__value">{{ formatCount(group.inProgressCount) }}</strong>
          </div>
        </div>
        <el-progress
          class="direction-progress"
          :percentage="barPercentage(group.cumulativeRate)"
          :status="(group.cumulativeRate ?? 0) >= 1 ? 'success' : undefined"
          :stroke-width="18"
          :format="() => formatPercent(group.cumulativeRate)"
        />
=======
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
>>>>>>> 67ea59fc012446865a59fbfd6c079e57e509c4af
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.indicator-progress-page { padding-bottom: 24px; }
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; }
.page-subtitle { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
<<<<<<< HEAD
.kpi-row { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.kpi-card :deep(.el-card__body) { padding: 16px 18px; }
.kpi-value { color: var(--el-color-primary); font-size: 30px; font-weight: 700; line-height: 38px; font-variant-numeric: tabular-nums; }
.kpi-value.is-good { color: var(--el-color-success); }
.kpi-value.is-bad { color: var(--el-color-danger); }
.kpi-label { margin-top: 4px; display: flex; align-items: center; gap: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
.kpi-tip { cursor: help; }
.direction-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 16px; }
.direction-card :deep(.el-card__body) { padding: 18px 20px; }
.direction-title { margin: 0 0 16px; padding-left: 10px; border-left: 3px solid var(--el-color-primary); font-size: 16px; font-weight: 600; line-height: 24px; }
.direction-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 18px; }
.direction-stat { min-width: 0; }
.direction-stat__label { display: block; color: var(--el-text-color-secondary); font-size: 12px; white-space: nowrap; }
.direction-stat__value { display: block; margin-top: 5px; color: var(--el-text-color-primary); font-size: 22px; line-height: 28px; font-variant-numeric: tabular-nums; }
.direction-stat__value.is-rate { color: var(--el-color-primary); }
.direction-stat__value.is-good { color: var(--el-color-success); }
.direction-progress :deep(.el-progress__text) { min-width: 50px; color: var(--el-color-primary); font-variant-numeric: tabular-nums; }
.empty-wrapper { padding: 40px 0; }

@media (max-width: 1300px) {
  .kpi-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 1100px) {
  .direction-grid { grid-template-columns: minmax(0, 1fr); }
=======
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
>>>>>>> 67ea59fc012446865a59fbfd6c079e57e509c4af
}

@media (max-width: 768px) {
  .page-header { align-items: flex-start; flex-direction: column; }
<<<<<<< HEAD
  .kpi-row { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .kpi-value { font-size: 24px; line-height: 30px; }
=======
  .summary-cards { grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .indicator-totals { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .year-row { grid-template-columns: 40px minmax(0, 1fr); }
  .year-values { grid-column: 2; text-align: left; }
>>>>>>> 67ea59fc012446865a59fbfd6c079e57e509c4af
}
</style>
