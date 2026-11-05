<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import BackButton from '@/components/BackButton.vue'
import YearProgressPanel from '@/views/stats/components/YearProgressPanel.vue'
import { getStatsTree, getStatsOverview, getIndicatorTrend } from '@/api/stats'
import { listIndicators } from '@/api/indicator'
import { getPlan } from '@/api/plan'
import type { IndicatorTrend, StatsOverview, TrendYearItem } from '@/types/stats'
import type { IndicatorTreeNode } from '@/types/indicator'
import type { StatCaliber } from '@/types/enums'
import { EMPTY_DISPLAY, formatCount, formatNumber, formatPercent } from '@/utils/format'
import { formatTargetValue, isPercentCaliber } from '@/utils/indicator'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { useUserStore } from '@/stores/user'

interface TrendWithDirection extends IndicatorTrend {
  directionName: string
  targetDirection: string | null
}

interface DirectionProgress {
  name: string
  trends: TrendWithDirection[]
  ratedCount: number
  completedCount: number
  completionRate: number | null
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const planId = computed(() => typeof route.params.planId === 'string' ? route.params.planId : '')
const canViewIndicatorProgress = computed(() => !userStore.isUnitOwnerOnly)

const loading = ref(false)
const planName = ref('')
const overview = ref<StatsOverview | null>(null)
const trends = ref<TrendWithDirection[]>([])
const directionOrder = ref<Record<string, number>>({})

/** 按主要建设方向汇总：完成率取方向内指标五年累计完成率的平均值。 */
const directionProgress = computed<DirectionProgress[]>(() => {
  const groups = new Map<string, TrendWithDirection[]>()
  for (const item of trends.value) {
    const name = item.directionName || '未归入建设方向'
    const list = groups.get(name)
    if (list) list.push(item)
    else groups.set(name, [item])
  }
  return [...groups.entries()]
    .map(([name, items]) => {
      const rates = items
        .map((item) => item.cumulativeRate)
        .filter((rate): rate is number => typeof rate === 'number' && Number.isFinite(rate))
      const completedCount = items.filter((item) => {
        const rate = item.cumulativeRate
        if (typeof rate !== 'number' || !Number.isFinite(rate)) return false
        return item.targetDirection === 'LOWER_BETTER' ? rate <= 1 : rate >= 1
      }).length
      return {
        name,
        trends: items,
        ratedCount: rates.length,
        completedCount,
        completionRate: rates.length > 0
          ? rates.reduce((sum, rate) => sum + rate, 0) / rates.length
          : null,
      }
    })
    .sort((left, right) =>
      (directionOrder.value[left.name] ?? Number.MAX_SAFE_INTEGER)
      - (directionOrder.value[right.name] ?? Number.MAX_SAFE_INTEGER)
      || left.name.localeCompare(right.name, 'zh-CN'))
})

function actualText(value: number | null | undefined, caliber: StatCaliber | null): string {
  if (value === null || value === undefined) return EMPTY_DISPLAY
  if (caliber === 'BOOLEAN') return value >= 1 ? '是' : '否'
  if (isPercentCaliber(caliber)) return formatPercent(value)
  return formatNumber(value)
}

function yearActualText(item: TrendWithDirection, yearItem: TrendYearItem): string {
  const value = item.statCaliber === 'GROWTH' ? yearItem.actualTotal : yearItem.actualValue
  return actualText(value, item.statCaliber)
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

function directionPercentage(direction: DirectionProgress): number {
  if (typeof direction.completionRate !== 'number') return 0
  return Math.min(100, Math.max(0, Number((direction.completionRate * 100).toFixed(1))))
}

function directionRateText(direction: DirectionProgress): string {
  return typeof direction.completionRate === 'number'
    ? formatPercent(direction.completionRate)
    : '暂无数据'
}

function directionStatus(direction: DirectionProgress): 'success' | undefined {
  return typeof direction.completionRate === 'number' && direction.completionRate >= 1
    ? 'success'
    : undefined
}

/** 请求代次：切换计划后丢弃旧计划的在途响应，避免覆盖新计划数据 */
let loadSeq = 0

async function load(silent = false): Promise<void> {
  const currentPlanId = planId.value
  if (!canViewIndicatorProgress.value || !currentPlanId) return
  const seq = ++loadSeq
  if (!silent) loading.value = true
  try {
    const [plan, tree, indicatorTree, stats] = await Promise.all([
      getPlan(currentPlanId),
      getStatsTree(currentPlanId),
      listIndicators(currentPlanId).catch(() => [] as IndicatorTreeNode[]),
      getStatsOverview(currentPlanId).catch(() => null),
    ])
    if (seq !== loadSeq) return
    planName.value = plan.name
    overview.value = stats

    const directionNameById = new Map<string, string>()
    const orders: Record<string, number> = {}
    let nextOrder = 0
    for (const node of indicatorTree) {
      if (node.level !== 'A') continue
      const name = node.directionName?.trim()
        ? node.directionName
        : '未归入建设方向'
      directionNameById.set(node.id, name)
      if (orders[name] === undefined) orders[name] = nextOrder++
    }
    directionOrder.value = orders

    const aNodes = tree.filter((node) => node.level === 'A')
    const targetDirectionById = new Map(aNodes.map((node) => [node.id, node.targetDirection ?? null]))
    const results = await Promise.allSettled(aNodes.map((node) => getIndicatorTrend(node.id)))
    if (seq !== loadSeq) return
    trends.value = results
      .filter((result): result is PromiseFulfilledResult<IndicatorTrend> => result.status === 'fulfilled')
      .map((result) => ({
        ...result.value,
        directionName: directionNameById.get(result.value.indicatorId) ?? '未归入建设方向',
        targetDirection: targetDirectionById.get(result.value.indicatorId) ?? null,
      }))
      .sort((a, b) => a.code.localeCompare(b.code, 'zh-CN', { numeric: true }))
    if (!silent && trends.value.length < aNodes.length) {
      ElMessage.warning('部分指标进度加载失败，可刷新重试')
    }
  } finally {
    if (seq === loadSeq && !silent) loading.value = false
  }
}

watch(planId, (next) => {
  planName.value = ''
  overview.value = null
  trends.value = []
  directionOrder.value = {}
  if (next) void load()
}, { immediate: true })

useAutoRefresh(() => load(true), { intervalMs: 60_000 })
</script>

<template>
  <div v-if="canViewIndicatorProgress" v-loading="loading" class="indicator-progress-page">
    <BackButton />
    <div class="page-header">
      <div>
        <h1 class="page-title">关键产出指标进度可视化</h1>
        <p class="page-subtitle">{{ planName }} · 主要建设方向完成情况、五年总目标与逐年完成量</p>
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

    <div v-if="!loading && trends.length === 0" class="empty-wrapper">
      <el-empty :image-size="90" description="暂无可量化的关键产出指标" />
    </div>

    <section v-if="directionProgress.length > 0" class="direction-section">
      <div class="section-head">
        <h2 class="section-title">主要建设方向完成情况</h2>
        <span class="section-hint">方向完成率 = 方向内各指标五年累计完成率的平均值</span>
      </div>
      <div class="direction-grid">
        <el-card
          v-for="direction in directionProgress"
          :key="direction.name"
          shadow="never"
          class="direction-card"
        >
          <div class="direction-card__head">
            <strong class="direction-card__name" :title="direction.name">{{ direction.name }}</strong>
            <span class="direction-card__count">
              <template v-if="direction.ratedCount > 0">
                已完成 {{ direction.completedCount }} / {{ direction.ratedCount }} 项指标
              </template>
              <template v-else>暂无可统计数据</template>
            </span>
          </div>
          <el-progress
            class="direction-progress"
            :percentage="directionPercentage(direction)"
            :status="directionStatus(direction)"
            :stroke-width="16"
            :text-inside="true"
            :format="() => directionRateText(direction)"
          />
          <div class="direction-card__meta">
            <span>指标 {{ direction.trends.length }} 项 · 可统计 {{ direction.ratedCount }} 项</span>
            <span>平均完成率 {{ formatPercent(direction.completionRate) }}</span>
          </div>
        </el-card>
      </div>
    </section>

    <section
      v-for="direction in directionProgress"
      :key="`detail-${direction.name}`"
      class="direction-group"
    >
      <div class="direction-group__head">
        <h2 class="direction-group__title">{{ direction.name }}</h2>
        <span>{{ direction.trends.length }} 项指标</span>
      </div>
      <div class="indicator-grid">
        <el-card
          v-for="item in direction.trends"
          :key="item.indicatorId"
          shadow="never"
          class="indicator-card"
        >
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
              :content="`${yearItem.year} 年目标：${formatTargetValue(yearItem.target, item.statCaliber)}；已完成：${yearActualText(item, yearItem)}`"
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
                  {{ yearActualText(item, yearItem) }} / {{ formatTargetValue(yearItem.target, item.statCaliber) }}
                </span>
              </div>
            </el-tooltip>
          </div>
        </el-card>
      </div>
    </section>
  </div>
  <el-result v-else icon="warning" title="暂无访问权限">
    <template #sub-title>你没有访问该页面的权限，如有疑问请联系平台管理员</template>
    <template #extra>
      <el-button type="primary" @click="router.push(userStore.homePath)">返回首页</el-button>
    </template>
  </el-result>
</template>

<style scoped>
.indicator-progress-page { padding-bottom: 24px; }
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; line-height: 28px; font-weight: 600; }
.page-subtitle { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.kpi-row { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.kpi-card :deep(.el-card__body) { padding: 16px 18px; }
.kpi-value { color: var(--el-color-primary); font-size: 30px; font-weight: 700; line-height: 38px; font-variant-numeric: tabular-nums; }
.kpi-value.is-good { color: var(--el-color-success); }
.kpi-value.is-bad { color: var(--el-color-danger); }
.kpi-label { margin-top: 4px; display: flex; align-items: center; gap: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
.kpi-tip { cursor: help; }
.empty-wrapper { padding: 40px 0; }
.direction-section { margin-bottom: 20px; }
.section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.section-title { margin: 0; font-size: 16px; font-weight: 600; }
.section-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.direction-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.direction-card :deep(.el-card__body) { padding: 14px 16px; }
.direction-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.direction-card__name { min-width: 0; overflow: hidden; color: var(--el-text-color-primary); font-size: 14px; line-height: 20px; text-overflow: ellipsis; white-space: nowrap; }
.direction-card__count { flex: none; color: var(--el-text-color-secondary); font-size: 12px; white-space: nowrap; }
.direction-progress { width: 100%; }
.direction-progress :deep(.el-progress-bar__innerText) { font-size: 11px; }
.direction-card__meta { display: flex; justify-content: space-between; gap: 8px; margin-top: 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.direction-group + .direction-group { margin-top: 20px; }
.direction-group__head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 10px; padding-bottom: 6px; border-bottom: 1px solid var(--el-border-color-lighter); }
.direction-group__title { margin: 0; font-size: 15px; font-weight: 600; }
.direction-group__head span { color: var(--el-text-color-secondary); font-size: 12px; }
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

@media (max-width: 1300px) {
  .kpi-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 1100px) {
  .indicator-grid { grid-template-columns: minmax(0, 1fr); }
}

@media (max-width: 768px) {
  .page-header { align-items: flex-start; flex-direction: column; }
  .kpi-row { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .kpi-value { font-size: 24px; line-height: 30px; }
  .section-head { flex-direction: column; gap: 4px; }
  .direction-grid { grid-template-columns: minmax(0, 1fr); }
  .direction-card__head { flex-direction: column; gap: 2px; }
  .direction-card__name { white-space: normal; }
  .indicator-totals { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .year-row { grid-template-columns: 40px minmax(0, 1fr); }
  .year-values { grid-column: 2; text-align: left; }
}
</style>
