<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import ProgressBar from '@/components/ProgressBar.vue'
import PageState from '@/components/PageState.vue'
import PlanSwitcher from '@/components/PlanSwitcher.vue'
import StatusTag from '@/components/StatusTag.vue'
import { getStatsTree, getStatsOverview, getIndicatorTrend } from '@/api/stats'
import { listIndicators } from '@/api/indicator'
import { getPlan, listPlans } from '@/api/plan'
import { formatCount, formatNumber, formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import type { IndicatorTrend, StatsOverview } from '@/types/stats'
import type { PlanCard, PlanDetail } from '@/types/plan'

interface TrendWithDirection extends IndicatorTrend {
  directionName: string
  targetDirection: string | null
}

interface DirectionProgress {
  name: string
  trends: TrendWithDirection[]
  ratedCount: number
  completedCount: number
  inProgressCount: number
  completionRate: number | null
}

const route = useRoute()
const router = useRouter()
const planId = computed(() => (typeof route.params.planId === 'string' ? route.params.planId : ''))

const loading = ref(false)
const plan = ref<PlanDetail | null>(null)
const plans = ref<PlanCard[]>([])
const overview = ref<StatsOverview | null>(null)
const trends = ref<TrendWithDirection[]>([])
const directionOrder = ref<Record<string, number>>({})

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
      const inProgressCount = items.filter((item) => {
        const rate = item.cumulativeRate
        if (typeof rate !== 'number') return false
        return item.targetDirection === 'LOWER_BETTER' ? rate > 1 : rate < 1
      }).length
      return {
        name,
        trends: items,
        ratedCount: rates.length,
        completedCount,
        inProgressCount,
        completionRate: rates.length > 0 ? rates.reduce((sum, rate) => sum + rate, 0) / rates.length : null,
      }
    })
    .sort((left, right) =>
      (directionOrder.value[left.name] ?? Number.MAX_SAFE_INTEGER)
      - (directionOrder.value[right.name] ?? Number.MAX_SAFE_INTEGER)
      || left.name.localeCompare(right.name, 'zh-CN'))
})

const progressRate = computed(() => overview.value?.cumulativeCompletionRate ?? null)
const achievedCount = computed(() => overview.value?.achievedCount ?? 0)
const totalCount = computed(() => overview.value?.taskCount ?? 0)
const unfinishedCount = computed(() => Math.max(0, totalCount.value - achievedCount.value))

let loadSeq = 0

async function loadPlans(): Promise<void> {
  try {
    plans.value = await listPlans()
  } catch {
    // 拦截器已提示
  }
}

async function load(): Promise<void> {
  const currentPlanId = planId.value
  if (!currentPlanId) return
  const seq = ++loadSeq
  loading.value = true
  try {
    const [planDetail, tree, indicatorTree, stats] = await Promise.all([
      getPlan(currentPlanId),
      getStatsTree(currentPlanId),
      listIndicators(currentPlanId).catch(() => []),
      getStatsOverview(currentPlanId).catch(() => null),
    ])
    if (seq !== loadSeq) return
    plan.value = planDetail
    overview.value = stats

    const directionNameById = new Map<string, string>()
    const orders: Record<string, number> = {}
    let nextOrder = 0
    for (const node of indicatorTree) {
      if (node.level !== 'A') continue
      const name = node.directionName?.trim() ? node.directionName : '未归入建设方向'
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
    if (trends.value.length < aNodes.length) showToast('部分指标进度加载失败，可刷新重试')
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function directionPercentage(direction: DirectionProgress): number {
  if (typeof direction.completionRate !== 'number') return 0
  return Math.min(100, Math.max(0, Number((direction.completionRate * 100).toFixed(1))))
}

function yearActualText(item: TrendWithDirection, year: IndicatorTrend['years'][number]): string {
  return actualValueText(item.statCaliber === 'GROWTH' ? year.actualTotal : year.actualValue, item.statCaliber)
}

function actualValueText(value: number | null | undefined, caliber: IndicatorTrend['statCaliber']): string {
  if (value === null || value === undefined) return '—'
  if (caliber === 'BOOLEAN') return value >= 1 ? '是' : '否'
  if (caliber === 'RATIO' || caliber === 'DIRECT_PERCENT') return formatPercent(value)
  return formatNumber(value)
}

watch(planId, () => {
  plan.value = null
  overview.value = null
  trends.value = []
  directionOrder.value = {}
  if (planId.value) void load()
}, { immediate: true })

void loadPlans()

function onPlanChange(planOption: { id: string }): void {
  router.replace(`/plans/${planOption.id}/indicator-progress`)
}
</script>

<template>
  <div class="page">
    <PlanSwitcher
      v-if="plans.length > 0"
      :model-value="planId"
      :plans="plans"
      @change="onPlanChange"
    />

    <PageState :loading="loading && !plan" loading-text="加载进度…">
      <template v-if="plan">
        <div class="page-head">
          <h1>指标进度可视化</h1>
          <p class="muted small">{{ plan.name }} · 主要建设方向完成情况与五年进度</p>
        </div>

        <div v-if="overview" class="kpi-grid">
          <div class="kpi-card kpi-card--main">
            <strong>{{ formatPercent(progressRate) }}</strong>
            <span>五年累计完成率</span>
          </div>
          <div class="kpi-card"><strong>{{ formatCount(totalCount) }}</strong><span>任务总数</span></div>
          <div class="kpi-card"><strong class="text-success">{{ formatCount(achievedCount) }}</strong><span>达标</span></div>
          <div class="kpi-card"><strong>{{ formatCount(unfinishedCount) }}</strong><span>未完成</span></div>
          <div class="kpi-card"><strong>{{ formatCount(overview.inProgressCount) }}</strong><span>进行中</span></div>
          <div class="kpi-card"><strong class="text-danger">{{ formatCount(overview.warningCount) }}</strong><span>滞后预警</span></div>
        </div>

        <div class="card">
          <div class="card-title"><span>五年累计总进度</span></div>
          <ProgressBar :rate="progressRate" :height="14" show-text />
          <p class="small muted progress-caption">五年累计指标完成进度（按指标累计完成率平均）</p>
        </div>

        <div v-if="directionProgress.length === 0" class="empty-state">暂无可量化的关键产出指标</div>

        <template v-else>
          <div class="section-title">主要建设方向完成情况</div>
          <div class="direction-grid">
            <div v-for="direction in directionProgress" :key="direction.name" class="direction-card">
              <div class="direction-name">{{ direction.name }}</div>
              <ProgressBar :rate="direction.completionRate" :height="10" show-text />
              <div class="direction-stats">
                <div><span>指标数量</span><strong>{{ direction.trends.length }}</strong></div>
                <div><span>五年完成率</span><strong>{{ formatPercent(direction.completionRate) }}</strong></div>
                <div><span>达标数量</span><strong class="text-success">{{ direction.completedCount }}</strong></div>
                <div><span>进行中</span><strong>{{ direction.inProgressCount }}</strong></div>
              </div>
            </div>
          </div>

          <div v-for="direction in directionProgress" :key="`detail-${direction.name}`" class="direction-group">
            <div class="section-title">{{ direction.name }} · {{ direction.trends.length }} 项</div>
            <div class="indicator-grid">
              <div v-for="item in direction.trends" :key="item.indicatorId" class="indicator-card" @click="router.push(`/indicators/${item.indicatorId}`)">
                <div class="row--between">
                  <span class="code">{{ item.code }}</span>
                  <span class="small text-brand">{{ formatPercent(item.cumulativeRate) }}</span>
                </div>
                <div class="indicator-name ellipsis-2">{{ item.name }}</div>
                <div class="indicator-totals">
                  <div><span>五年目标</span><strong>{{ formatTargetValue(item.totalTarget, item.statCaliber) }}</strong></div>
                  <div><span>累计完成</span><strong>{{ actualValueText(item.cumulativeActual, item.statCaliber) }}</strong></div>
                  <div><span>完成率</span><strong class="text-brand">{{ formatPercent(item.cumulativeRate) }}</strong></div>
                </div>
                <div class="year-list">
                  <div v-for="year in item.years" :key="year.year" class="year-row">
                    <span class="year-label">{{ year.year }}</span>
                    <ProgressBar :rate="year.completionRate" :height="6" class="grow" />
                    <span class="year-values">{{ yearActualText(item, year) }} / {{ formatTargetValue(year.target, item.statCaliber) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </template>
    </PageState>
  </div>
</template>

<style scoped>
.page-head {
  margin: 12px 0;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.page-head p {
  margin: 4px 0 0;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}
.kpi-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.kpi-card strong {
  color: var(--brand);
  font-size: 22px;
  line-height: 28px;
}
.kpi-card span {
  color: var(--text-secondary);
  font-size: 11px;
}
.kpi-card--main {
  grid-column: span 2;
}
.progress-caption {
  margin: 6px 0 0;
}
.direction-grid,
.indicator-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}
.direction-card {
  padding: 12px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.direction-name {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
}
.direction-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
  margin-top: 10px;
}
.direction-stats > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px;
  border-radius: 8px;
  background: #f8fafd;
}
.direction-stats span {
  color: var(--text-secondary);
  font-size: 10px;
}
.direction-stats strong {
  font-size: 13px;
}
.direction-group {
  margin-top: 14px;
}
.indicator-card {
  padding: 12px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
}
.indicator-name {
  margin: 4px 0 8px;
  font-size: 14px;
  line-height: 20px;
}
.indicator-totals {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f5f8ff;
}
.indicator-totals > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.indicator-totals span {
  color: var(--text-secondary);
  font-size: 10px;
}
.indicator-totals strong {
  font-size: 13px;
}
.year-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}
.year-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.year-label {
  flex: 0 0 36px;
  color: var(--text-secondary);
  font-size: 11px;
}
.year-values {
  flex: 0 0 auto;
  color: var(--text-secondary);
  font-size: 10px;
  white-space: nowrap;
}
</style>
