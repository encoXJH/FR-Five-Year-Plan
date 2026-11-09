<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import PageState from '@/components/PageState.vue'
import PlanSwitcher from '@/components/PlanSwitcher.vue'
import TaskDetailSheet from '@/components/TaskDetailSheet.vue'
import { listPlans, getPlan } from '@/api/plan'
import { getStatsByUnit, getStatsOverview, getStatsTree, getIndicatorTrend } from '@/api/stats'
import { getYearSettlement } from '@/api/settlement'
import { CHART_ACTUAL_COLOR, CHART_TARGET_COLOR, CHART_TIME_PROGRESS_COLOR, useEChart } from '@/utils/chart'
import { formatCount, formatNumber, formatPercent } from '@/utils/format'
import { formatTargetValue, isPercentCaliber } from '@/utils/indicator'
import { progressPercentage } from '@/utils/task'
import { sortUnitStatsByYear, unitStatRate } from '@/utils/stats'
import { getCurrentPlan, setCurrentPlan } from '@/utils/currentPlan'
import { useUserStore } from '@/stores/user'
import type { PlanCard, PlanDetail } from '@/types/plan'
import type {
  IndicatorTrend,
  StatsOverview,
  StatsTreeNode,
  UnitStatItem,
  YearSettlement,
} from '@/types/stats'
import type { StatCaliber } from '@/types/enums'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isUnitOwnerOnly = computed(() => userStore.isUnitOwnerOnly)

const plans = ref<PlanCard[]>([])
const planId = ref<string>(typeof route.query.planId === 'string' ? route.query.planId : '')
const plan = ref<PlanDetail | null>(null)
const year = ref<number | null>(route.query.year ? Number(route.query.year) : null)
const yearPickerVisible = ref(false)
const loading = ref(false)
const overview = ref<StatsOverview | null>(null)
const byUnit = ref<UnitStatItem[]>([])
const tree = ref<StatsTreeNode[]>([])

const yearOptions = computed(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let y = plan.value.startYear; y <= plan.value.endYear; y += 1) list.push(y)
  return list
})
const yearLabel = computed(() => (year.value === null ? '五年累计' : `${year.value} 年`))
const planName = computed(() => plan.value?.name ?? plans.value.find((item) => item.id === planId.value)?.name ?? '')

let statsRequestSequence = 0

async function loadStats(): Promise<void> {
  if (!planId.value) return
  const requestedPlanId = planId.value
  const requestedYear = year.value
  const sequence = ++statsRequestSequence
  loading.value = true
  try {
    const y = requestedYear ?? undefined
    const [o, u, t] = await Promise.all([
      getStatsOverview(requestedPlanId, y),
      getStatsByUnit(requestedPlanId, y),
      getStatsTree(requestedPlanId, y),
    ])
    if (sequence !== statsRequestSequence) return
    overview.value = o
    byUnit.value = u
    tree.value = t
    await nextTick()
    renderUnitChart()
    renderComboChart()
  } finally {
    if (sequence === statsRequestSequence) loading.value = false
  }
}

async function loadPlanContext(): Promise<void> {
  if (!planId.value) return
  plan.value = await getPlan(planId.value)
}

async function loadPlans(): Promise<void> {
  try {
    plans.value = await listPlans()
  } catch {
    // 拦截器已提示
  }
}

function syncQuery(): void {
  void router.replace({
    query: {
      ...route.query,
      planId: planId.value || undefined,
      year: year.value ?? undefined,
    },
  })
}

async function onPlanChange(item: { id: string; name: string }): Promise<void> {
  planId.value = item.id
  setCurrentPlan(item)
  year.value = null
  syncQuery()
  await loadPlanContext()
  await loadStats()
}

function chooseYear(value: number): void {
  year.value = value === 0 ? null : value
  yearPickerVisible.value = false
  syncQuery()
  void loadStats()
}

// 图表
const {
  container: unitChartEl,
  setOption: setUnitChartOption,
} = useEChart()
const {
  container: comboChartEl,
  setOption: setComboChartOption,
} = useEChart()

function renderUnitChart(): void {
  const selectedYear = year.value
  const sorted = sortUnitStatsByYear(byUnit.value, selectedYear)
  setUnitChartOption({
    animation: false,
    grid: { left: 6, right: 46, top: 6, bottom: 6, containLabel: true },
    xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%', fontSize: 10 } },
    yAxis: {
      type: 'category',
      inverse: true,
      data: sorted.map((item) => item.unit?.name ?? '—'),
      axisLabel: { width: 84, overflow: 'truncate', fontSize: 10 },
    },
    series: [{
      type: 'bar',
      barWidth: 12,
      data: sorted.map((item) => {
        const rate = unitStatRate(item, selectedYear)
        return {
          value: rate == null ? 0 : Number((rate * 100).toFixed(1)),
          itemStyle: { color: '#2B5BC0', borderRadius: [0, 3, 3, 0] },
        }
      }),
      label: {
        show: true,
        position: 'right',
        fontSize: 10,
        formatter: (p: { dataIndex: number }) => {
          const item = sorted[p.dataIndex]
          const rate = item ? unitStatRate(item, selectedYear) : null
          return rate == null ? '—' : `${(rate * 100).toFixed(1)}%`
        },
      },
    }],
  })
}

function renderComboChart(): void {
  const rows = byUnit.value
  setComboChartOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { top: 0, textStyle: { fontSize: 10 } },
    grid: { left: 6, right: 36, top: 28, bottom: 6, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map((item) => item.unit?.name ?? '—'),
      axisLabel: { interval: 0, rotate: rows.length > 6 ? 30 : 0, width: 64, overflow: 'truncate', fontSize: 9 },
    },
    yAxis: [
      { type: 'value', name: '任务数', minInterval: 1, axisLabel: { fontSize: 9 } },
      { type: 'value', name: '完成率', max: 100, axisLabel: { formatter: '{value}%', fontSize: 9 } },
    ],
    series: [
      {
        name: '目标',
        type: 'bar',
        barMaxWidth: 16,
        itemStyle: { color: CHART_TARGET_COLOR },
        data: rows.map((item) => item.taskCount),
      },
      {
        name: '完成',
        type: 'bar',
        barMaxWidth: 16,
        itemStyle: { color: CHART_ACTUAL_COLOR },
        data: rows.map((item) => item.achievedCount ?? 0),
      },
      {
        name: '完成率',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 4,
        lineStyle: { color: CHART_TIME_PROGRESS_COLOR },
        itemStyle: { color: CHART_TIME_PROGRESS_COLOR },
        data: rows.map((row) => (row.completionRate == null ? null : Number((row.completionRate * 100).toFixed(1)))),
      },
    ],
  })
}

// 树
const expandedIds = ref<Set<string>>(new Set())
function toggleNode(node: StatsTreeNode): void {
  const next = new Set(expandedIds.value)
  if (next.has(node.id)) next.delete(node.id)
  else next.add(node.id)
  expandedIds.value = next
}
function nodeTargetText(node: StatsTreeNode): string {
  return formatTargetValue(node.target, node.statCaliber ?? ('INCREMENT' as StatCaliber))
}
function nodeActualText(node: StatsTreeNode): string {
  const value = node.statCaliber === 'GROWTH' ? node.actualTotal : node.actualValue
  if (value == null) return '—'
  if (node.statCaliber && isPercentCaliber(node.statCaliber)) return formatPercent(value)
  return formatNumber(value)
}

// 趋势
const trendVisible = ref(false)
const trendLoading = ref(false)
const indicatorTrend = ref<IndicatorTrend | null>(null)
let trendSequence = 0
async function openTrend(node: StatsTreeNode): Promise<void> {
  if (node.level !== 'A') return
  const sequence = ++trendSequence
  indicatorTrend.value = null
  trendVisible.value = true
  trendLoading.value = true
  try {
    const result = await getIndicatorTrend(node.id)
    if (sequence === trendSequence) indicatorTrend.value = result
  } finally {
    if (sequence === trendSequence) trendLoading.value = false
  }
}

// 任务详情
const taskSheetVisible = ref(false)
const taskSheetId = ref<string | null>(null)
function openTask(node: StatsTreeNode): void {
  if (node.level === 'B' && node.taskId) {
    taskSheetId.value = node.taskId
    taskSheetVisible.value = true
  }
}

// 核算结果
const settlementVisible = ref(false)
const settlementYear = ref<number | null>(null)
const settlement = ref<YearSettlement | null>(null)
const settlementLoading = ref(false)
const lockedYears = computed(() => plan.value?.years.filter((item) => item.status === 'LOCKED').map((item) => item.year) ?? [])

async function openSettlement(): Promise<void> {
  settlementVisible.value = true
  settlementYear.value = year.value ?? lockedYears.value[0] ?? null
  settlement.value = null
  if (settlementYear.value !== null) await loadSettlement()
}
async function loadSettlement(): Promise<void> {
  if (!planId.value || settlementYear.value === null) return
  settlementLoading.value = true
  try {
    settlement.value = await getYearSettlement(planId.value, settlementYear.value)
  } catch {
    settlement.value = null
    showToast(`${settlementYear.value} 年度暂无核算结果（可能尚未核算）`)
  } finally {
    settlementLoading.value = false
  }
}
const settlementSummary = computed(() => {
  const items = settlement.value?.items ?? []
  return {
    total: items.length,
    achieved: items.filter((item) => item.isAchieved === true).length,
    notAchieved: items.filter((item) => item.isAchieved === false).length,
  }
})

// 导出
const printing = ref(false)
const generatedAt = ref('')
async function exportPdf(): Promise<void> {
  if (!planId.value || printing.value) return
  printing.value = true
  generatedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
  const originalTitle = document.title
  document.title = `统计报表-${planName.value || '计划'}-${yearLabel.value}`
  await nextTick()
  document.body.classList.add('stats-print-mode')
  try {
    window.print()
  } finally {
    document.body.classList.remove('stats-print-mode')
    document.title = originalTitle
    printing.value = false
  }
}

// 年度进度
const annualCompletionRate = computed(() => {
  const total = overview.value?.taskCount ?? 0
  return total > 0 ? (overview.value?.achievedCount ?? 0) / total : null
})
const progressRate = computed(() =>
  year.value === null ? (overview.value?.cumulativeCompletionRate ?? null) : annualCompletionRate.value,
)
const unfinishedCount = computed(() =>
  Math.max(0, (overview.value?.taskCount ?? 0) - (overview.value?.achievedCount ?? 0)),
)

watch(planId, async (id) => {
  plan.value = null
  overview.value = null
  byUnit.value = []
  tree.value = []
  if (id) {
    await loadPlanContext()
    await loadStats()
  }
}, { immediate: true })

onMounted(async () => {
  await loadPlans()
  if (!planId.value && plans.value.length > 0) {
    const current = getCurrentPlan()
    const fallback = plans.value.find((item) => item.id === current?.id)
      ?? plans.value.find((item) => item.status === 'ACTIVE')
      ?? plans.value[0]
    planId.value = fallback.id
    syncQuery()
  }
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>统计报表</h1>
        <p class="muted small">{{ planName }}</p>
      </div>
      <div v-if="!isUnitOwnerOnly" class="head-actions">
        <van-button size="small" type="primary" :loading="printing" :disabled="!planId" @click="exportPdf">
          导出 PDF
        </van-button>
        <van-button size="small" @click="openSettlement">年度核算</van-button>
      </div>
    </div>

    <PlanSwitcher v-if="plans.length > 0" :model-value="planId" :plans="plans" @change="onPlanChange" />

    <div class="year-bar">
      <button type="button" class="filter-btn" @click="yearPickerVisible = true">
        {{ yearLabel }}
        <van-icon name="arrow-down" size="11" />
      </button>
      <span class="small muted">五年累计使用累计口径，具体年度使用所选年度达标口径</span>
    </div>

    <PageState :loading="loading && !overview" :empty="!overview" empty-text="暂无统计数据">
      <template v-if="overview">
        <div class="kpi-grid">
          <div class="kpi-card">
            <strong>{{ formatPercent(progressRate) }}</strong>
            <span>{{ year === null ? '五年累计完成率' : '当年整体完成率' }}</span>
          </div>
          <div class="kpi-card">
            <strong>{{ formatPercent(overview.timeProgress) }}</strong>
            <span>时间进度</span>
          </div>
          <div class="kpi-card">
            <strong>{{ formatCount(overview.taskCount) }}</strong>
            <span>任务总数</span>
          </div>
          <div class="kpi-card">
            <strong class="text-success">{{ formatCount(overview.achievedCount) }}</strong>
            <span>达标</span>
          </div>
          <div class="kpi-card">
            <strong class="text-danger">{{ formatCount(overview.notAchievedCount) }}</strong>
            <span>未达标</span>
          </div>
          <div class="kpi-card">
            <strong>{{ formatCount(overview.inProgressCount) }}</strong>
            <span>进行中</span>
          </div>
        </div>

        <div class="card">
          <div class="card-title">
            <span>{{ year === null ? '五年累计总进度' : `${year} 年总进度` }}</span>
            <span class="small muted">
              {{ year === null ? formatPercent(overview.cumulativeCompletionRate) : `已完成 ${overview.achievedCount} / 共 ${overview.taskCount} 项任务` }}
            </span>
          </div>
          <ProgressBar :rate="progressRate" :height="14" show-text />
          <div class="progress-detail">
            <div><span>已完成任务</span><strong class="text-success">{{ formatCount(overview.achievedCount) }}</strong></div>
            <div><span>未完成任务</span><strong :class="unfinishedCount > 0 ? 'text-danger' : ''">{{ formatCount(unfinishedCount) }}</strong></div>
            <div><span>约束性达标</span><strong>{{ overview.constraintAchieved }} / {{ overview.constraintTotal }}</strong></div>
            <div><span>发展性达标</span><strong>{{ overview.developmentAchieved }} / {{ overview.developmentTotal }}</strong></div>
            <div><span>待补做</span><strong class="text-danger">{{ overview.makeupPendingCount }}</strong></div>
            <div><span>滞后预警</span><strong class="text-warning">{{ overview.warningCount }}</strong></div>
          </div>
        </div>

        <div class="card">
          <div class="card-title"><span>执行单元完成率排名</span></div>
          <div ref="unitChartEl" class="chart chart--rank" />
        </div>

        <div class="card">
          <div class="card-title"><span>执行单元目标与完成</span></div>
          <div ref="comboChartEl" class="chart chart--combo" />
          <div class="unit-list">
            <button
              v-for="item in sortUnitStatsByYear(byUnit, year)"
              :key="item.unit?.id ?? item.rank"
              type="button"
              class="unit-row"
            >
              <span class="rank">{{ item.rank }}</span>
              <span class="grow ellipsis">{{ item.unit?.name ?? '—' }}</span>
              <span class="small muted">{{ item.achievedCount }} / {{ item.taskCount }} 项</span>
              <strong class="small text-brand">{{ formatPercent(unitStatRate(item, year)) }}</strong>
            </button>
          </div>
        </div>

        <div class="card">
          <div class="card-title">
            <span>指标下钻（A → B）</span>
            <span class="small muted">{{ yearLabel }}</span>
          </div>
          <div v-if="tree.length === 0" class="empty-state">暂无指标统计</div>
          <div v-else class="tree-list">
            <div v-for="node in tree" :key="node.id" class="tree-node" :class="{ 'is-b': node.level === 'B' }">
              <div class="tree-head" @click="node.level === 'A' ? openTrend(node) : openTask(node)">
                <div class="row">
                  <button
                    v-if="(node.children ?? []).length > 0"
                    type="button"
                    class="expand-icon"
                    @click.stop="toggleNode(node)"
                  >
                    <van-icon :name="expandedIds.has(node.id) ? 'arrow-down' : 'arrow'" size="11" />
                  </button>
                  <span class="code">{{ node.code }}</span>
                  <span class="ellipsis node-name">{{ node.name }}</span>
                </div>
                <StatusTag
                  v-if="node.achieveStatus"
                  kind="AchieveStatus"
                  :value="node.achieveStatus"
                />
                <StatusTag v-else-if="node.yearStatus" kind="TaskYearStatus" :value="node.yearStatus" />
              </div>
              <div class="tree-meta">
                <span>目标 {{ nodeTargetText(node) }}</span>
                <span>完成 {{ nodeActualText(node) }}</span>
                <span>{{ formatPercent(node.completionRate) }}</span>
                <span v-if="node.unit" class="muted">{{ node.unit.name }}</span>
              </div>
              <ProgressBar :rate="node.cumulativeRate ?? node.completionRate" :height="5" />
              <template v-if="expandedIds.has(node.id) && (node.children ?? []).length > 0">
                <div class="tree-children">
                  <div
                    v-for="child in node.children"
                    :key="child.id"
                    class="tree-node is-b"
                  >
                    <div class="tree-head" @click="openTask(child)">
                      <div class="row">
                        <span class="code">{{ child.code }}</span>
                        <span class="ellipsis node-name">{{ child.name }}</span>
                      </div>
                      <StatusTag v-if="child.yearStatus" kind="TaskYearStatus" :value="child.yearStatus" />
                    </div>
                    <div class="tree-meta">
                      <span>目标 {{ nodeTargetText(child) }}</span>
                      <span>完成 {{ nodeActualText(child) }}</span>
                      <span>{{ formatPercent(child.completionRate) }}</span>
                    </div>
                  </div>
                </div>
              </template>
            </div>
          </div>
        </div>
      </template>
    </PageState>

    <van-popup v-model:show="yearPickerVisible" position="bottom" round>
      <van-picker
        :columns="[
          { text: '五年累计', value: 0 },
          ...yearOptions.map((item) => ({ text: `${item} 年`, value: item })),
        ]"
        @confirm="(value: { selectedOptions: Array<{ value: number }> }) => chooseYear(value.selectedOptions[0]?.value ?? 0)"
        @cancel="yearPickerVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="trendVisible" position="bottom" round :style="{ height: '72vh' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>指标详情</strong>
          <button type="button" class="close-btn" @click="trendVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="sheet-body">
          <div v-if="trendLoading" class="skeleton-block">加载中…</div>
          <template v-else-if="indicatorTrend">
            <h3 class="trend-title">{{ indicatorTrend.code }} {{ indicatorTrend.name }}</h3>
            <div class="kv-list">
              <div class="kv"><span class="kv__label">统计口径</span><span class="kv__value">{{ indicatorTrend.statCaliber ?? '—' }}</span></div>
              <div class="kv"><span class="kv__label">五年目标</span><span class="kv__value">{{ formatTargetValue(indicatorTrend.totalTarget, indicatorTrend.statCaliber) }}</span></div>
              <div class="kv"><span class="kv__label">累计完成</span><span class="kv__value">{{ indicatorTrend.cumulativeActual ?? '—' }}</span></div>
              <div class="kv"><span class="kv__label">五年完成率</span><span class="kv__value text-brand">{{ formatPercent(indicatorTrend.cumulativeRate) }}</span></div>
            </div>
            <div class="trend-list">
              <div v-for="item in indicatorTrend.years" :key="item.year" class="trend-item">
                <div class="row--between">
                  <strong>{{ item.year }} 年</strong>
                  <StatusTag v-if="item.achieveStatus" kind="AchieveStatus" :value="item.achieveStatus" />
                </div>
                <div class="small muted">
                  目标 {{ formatTargetValue(item.target, indicatorTrend.statCaliber) }} · 完成
                  {{ formatTargetValue(indicatorTrend.statCaliber === 'GROWTH' ? item.actualTotal : item.actualValue, indicatorTrend.statCaliber) }}
                </div>
                <ProgressBar :rate="item.completionRate" :height="6" show-text />
              </div>
            </div>
          </template>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="settlementVisible" position="bottom" round :style="{ height: '82vh' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>年度核算结果</strong>
          <button type="button" class="close-btn" @click="settlementVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="sheet-body">
          <div v-if="lockedYears.length === 0" class="empty-state">暂无已核算年度</div>
          <template v-else>
            <div class="filter-scroll">
              <button
                v-for="item in lockedYears"
                :key="item"
                type="button"
                class="filter-chip"
                :class="{ 'is-active': settlementYear === item }"
                @click="settlementYear = item; loadSettlement()"
              >{{ item }} 年</button>
            </div>
            <div v-if="settlementLoading" class="skeleton-block">加载中…</div>
            <template v-else-if="settlement">
              <div class="settle-summary">
                <span class="chip chip--success">达标 {{ settlementSummary.achieved }}</span>
                <span class="chip chip--danger">未达标 {{ settlementSummary.notAchieved }}</span>
                <span class="small muted">共 {{ settlementSummary.total }} 条任务</span>
              </div>
              <div class="settle-list">
                <div v-for="row in settlement.items" :key="row.taskId" class="settle-item">
                  <div class="row--between">
                    <strong>{{ row.taskCode }}</strong>
                    <StatusTag kind="TaskYearStatus" :value="row.yearStatus" />
                  </div>
                  <div class="small muted ellipsis">{{ row.indicator?.name ?? '—' }} · {{ row.unit?.name ?? '—' }}</div>
                  <div class="small">完成率 {{ formatPercent(row.completionRate) }}</div>
                </div>
              </div>
            </template>
          </template>
        </div>
      </div>
    </van-popup>

    <TaskDetailSheet v-model="taskSheetVisible" :task-id="taskSheetId" />
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin: 2px 0 10px;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.page-head p {
  margin: 3px 0 0;
}
.head-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.year-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
}
.filter-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 17px;
  background: #fff;
  color: var(--text);
  font-size: 12px;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  margin-bottom: 10px;
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
  font-size: 21px;
}
.kpi-card span {
  color: var(--text-secondary);
  font-size: 11px;
}
.progress-detail {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 10px;
}
.progress-detail > div {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 8px;
  border-radius: 8px;
  background: #f8fafd;
}
.progress-detail span {
  color: var(--text-secondary);
  font-size: 10px;
}
.progress-detail strong {
  font-size: 14px;
}
.chart {
  width: 100%;
}
.chart--rank {
  height: 300px;
}
.chart--combo {
  height: 280px;
}
.unit-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 10px;
}
.unit-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 6px 4px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
}
.rank {
  width: 22px;
  color: var(--text-placeholder);
  font-size: 11px;
  text-align: center;
}
.tree-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tree-node {
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}
.tree-node.is-b {
  background: #f8fafd;
  border-color: #eef2f8;
}
.tree-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-height: 32px;
}
.expand-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 4px;
  background: #f1f4f9;
  color: var(--brand);
}
.code {
  flex-shrink: 0;
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  font-weight: 600;
}
.node-name {
  min-width: 0;
  font-size: 13px;
}
.tree-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin: 4px 0;
  color: var(--text);
  font-size: 11px;
}
.tree-children {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
  padding-left: 10px;
  border-left: 2px solid #e3eaf5;
}
.sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  font-size: 16px;
}
.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text-secondary);
}
.sheet-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px calc(16px + var(--safe-bottom));
}
.trend-title {
  margin: 0 0 10px;
  font-size: 16px;
}
.trend-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}
.trend-item {
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafd;
}
.settle-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.settle-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.settle-item {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
}
</style>
