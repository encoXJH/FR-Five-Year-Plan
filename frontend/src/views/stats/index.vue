<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ResponsiveRecordView from '@/components/ResponsiveRecordView.vue'
import StatusTag from '@/components/StatusTag.vue'
import YearProgressPanel from './components/YearProgressPanel.vue'
import { listPlans, getPlan } from '@/api/plan'
import {
  getStatsByUnit,
  getStatsOverview,
  getStatsTree,
  getIndicatorTrend,
} from '@/api/stats'
import { getYearSettlement } from '@/api/settlement'
import {
  CHART_ACTUAL_COLOR,
  CHART_TARGET_COLOR,
  CHART_TIME_PROGRESS_COLOR,
  useEChart,
} from '@/utils/chart'
import { EMPTY_DISPLAY, formatCount, formatNumber, formatPercent } from '@/utils/format'
import { formatTargetValue, isPercentCaliber } from '@/utils/indicator'
import { progressPercentage } from '@/utils/task'
import { getCurrentPlan, setCurrentPlan } from '@/utils/currentPlan'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { useUserStore } from '@/stores/user'
import type { PlanCard, PlanDetail } from '@/types/plan'
import type {
  IndicatorTrend,
  StatsOverview,
  StatsTreeNode,
  TrendYearItem,
  UnitStatItem,
  YearSettlement,
} from '@/types/stats'
import type { StatCaliber } from '@/types/enums'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isUnitOwnerOnly = computed(() => userStore.isUnitOwnerOnly)

const trendVisible = ref(false)
const trendLoading = ref(false)
const indicatorTrend = ref<IndicatorTrend | null>(null)
let trendRequestSequence = 0

async function openIndicatorDetail(id: string): Promise<void> {
  const sequence = ++trendRequestSequence
  indicatorTrend.value = null
  trendVisible.value = true
  trendLoading.value = true
  try {
    const result = await getIndicatorTrend(id)
    if (sequence === trendRequestSequence) indicatorTrend.value = result
  } finally {
    if (sequence === trendRequestSequence) trendLoading.value = false
  }
}

type StatsPlan = Pick<PlanCard, 'id' | 'name' | 'startYear' | 'endYear' | 'status'>
  & Partial<Pick<PlanDetail, 'years'>>

const plans = ref<StatsPlan[]>([])
const planId = ref<string>((route.query.planId as string) || '')
const plan = ref<StatsPlan | null>(null)
/** null 表示五年累计 */
const year = ref<number | null>(route.query.year ? Number(route.query.year) : null)
const yearSelection = computed({
  get: () => year.value ?? 0,
  set: (value: number) => {
    year.value = value === 0 ? null : value
  },
})

const loading = ref(false)
const overview = ref<StatsOverview | null>(null)
const byUnit = ref<UnitStatItem[]>([])
const tree = ref<StatsTreeNode[]>([])

type LazyStatsNode = Omit<StatsTreeNode, 'children'> & {
  children: LazyStatsNode[]
  hasChildren: boolean
}

const statsTableRef = ref<{ updateKeyChildren: (id: string, children: LazyStatsNode[]) => void } | null>(null)
const expandedIds = reactive(new Set<string>())

function toLazyNode(node: StatsTreeNode): LazyStatsNode {
  const { children, ...fields } = node
  return { ...fields, children: [], hasChildren: (children?.length ?? 0) > 0 }
}

const childrenById = computed<Record<string, LazyStatsNode[]>>(() => {
  const map: Record<string, LazyStatsNode[]> = {}
  const walk = (nodes: StatsTreeNode[]): void => {
    for (const node of nodes) {
      map[node.id] = (node.children ?? []).map(toLazyNode)
      if (node.children?.length) walk(node.children)
    }
  }
  walk(tree.value)
  return map
})

const lazyTree = computed(() => tree.value.map(toLazyNode))

const mobileTreeRows = computed<(StatsTreeNode & { depth: number })[]>(() => {
  const rows: (StatsTreeNode & { depth: number })[] = []
  const walk = (nodes: StatsTreeNode[], depth: number): void => {
    for (const node of nodes) {
      rows.push({ ...node, depth })
      if (node.children?.length && expandedIds.has(node.id)) walk(node.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return rows
})

function loadChildren(row: LazyStatsNode, _node: unknown, resolve: (children: LazyStatsNode[]) => void): void {
  resolve(childrenById.value[row.id] ?? [])
}

function handleExpandChange(row: StatsTreeNode, expanded: boolean): void {
  if (expanded) expandedIds.add(row.id)
  else expandedIds.delete(row.id)
}

async function syncExpandedNodes(): Promise<void> {
  await nextTick()
  for (const id of expandedIds) {
    const children = childrenById.value[id]
    if (children) statsTableRef.value?.updateKeyChildren(id, children)
    else expandedIds.delete(id)
  }
}

const yearOptions = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let y = plan.value.startYear; y <= plan.value.endYear; y += 1) list.push(y)
  return list
})

const planName = computed(() => plan.value?.name ?? plans.value.find((p) => p.id === planId.value)?.name ?? '')
const emptyDescription = '暂无统计数据（计划未选择或接口未就绪）'

const detailRows = computed(() => byUnit.value.map((item) => ({
  key: item.unit?.id ?? item.unit?.name ?? '',
  name: item.unit?.name ?? EMPTY_DISPLAY,
  taskCount: item.taskCount,
  achievedCount: item.achievedCount,
  completionRate: item.completionRate,
  cumulativeRate: item.cumulativeRate,
})))

async function loadPlanContext(): Promise<void> {
  if (!planId.value) return
  plan.value = await getPlan(planId.value)
}

let chartFrame: number | null = null

async function loadStats(silent = false): Promise<void> {
  if (!planId.value) return
  if (!silent) loading.value = true
  try {
    const y = year.value ?? undefined
    const [o, u, t] = await Promise.all([
      getStatsOverview(planId.value, y),
      getStatsByUnit(planId.value, y),
      getStatsTree(planId.value, y),
    ])
    overview.value = o
    byUnit.value = u
    tree.value = t
    await syncExpandedNodes()
    if (chartFrame !== null) cancelAnimationFrame(chartFrame)
    chartFrame = requestAnimationFrame(() => {
      chartFrame = null
      renderUnitChart()
      renderComboChart()
    })
  } finally {
    if (!silent) loading.value = false
  }
}

function syncQuery(): void {
  router.replace({
    query: {
      ...route.query,
      planId: planId.value || undefined,
      year: year.value ?? undefined,
    },
  })
}

function handlePlanChange(): void {
  const selected = plans.value.find((p) => p.id === planId.value)
  if (selected) setCurrentPlan(selected)
  year.value = null
  syncQuery()
  loadPlanContext().then(() => loadStats())
}

function handleYearChange(): void {
  syncQuery()
  loadStats()
}

// ---------- 单元排名图 ----------
const unitChart = useEChart()

function renderUnitChart(): void {
  const sorted = [...byUnit.value].sort((a, b) => (b.cumulativeRate ?? -1) - (a.cumulativeRate ?? -1))
  unitChart.setOption({
    animation: false,
    grid: { left: 8, right: 48, top: 8, bottom: 8, containLabel: true },
    xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    yAxis: {
      type: 'category',
      inverse: true,
      data: sorted.map((item) => item.unit?.name ?? EMPTY_DISPLAY),
      axisLabel: { width: 110, overflow: 'truncate' },
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        data: sorted.map((item) => ({
          value: item.cumulativeRate == null ? 0 : Number((item.cumulativeRate * 100).toFixed(1)),
          itemStyle: { color: '#2B5BC0', borderRadius: [0, 3, 3, 0] },
        })),
        label: {
          show: true,
          position: 'right',
          formatter: (p: { dataIndex: number }) =>
            sorted[p.dataIndex]?.cumulativeRate == null
              ? EMPTY_DISPLAY
              : `${(sorted[p.dataIndex].cumulativeRate! * 100).toFixed(1)}%`,
        },
      },
    ],
  })
}

// ---------- 执行单元目标 vs 完成（柱）+ 完成率（折线，右轴） ----------
const comboChart = useEChart()

function renderComboChart(): void {
  const rows = detailRows.value
  comboChart.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { top: 0 },
    grid: { left: 8, right: 48, top: 32, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map((row) => row.name),
      axisLabel: { interval: 0, rotate: rows.length > 6 ? 30 : 0, width: 90, overflow: 'truncate' },
    },
    yAxis: [
      { type: 'value', name: '任务数', minInterval: 1 },
      { type: 'value', name: '完成率', max: 100, axisLabel: { formatter: '{value}%' } },
    ],
    series: [
      {
        name: '目标',
        type: 'bar',
        barMaxWidth: 24,
        itemStyle: { color: CHART_TARGET_COLOR },
        data: rows.map((row) => row.taskCount),
      },
      {
        name: '完成',
        type: 'bar',
        barMaxWidth: 24,
        itemStyle: { color: CHART_ACTUAL_COLOR },
        data: rows.map((row) => row.achievedCount ?? 0),
      },
      {
        name: '完成率',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 6,
        lineStyle: { color: CHART_TIME_PROGRESS_COLOR },
        itemStyle: { color: CHART_TIME_PROGRESS_COLOR },
        data: rows.map((row) =>
          row.completionRate == null ? null : Number((row.completionRate * 100).toFixed(1)),
        ),
      },
    ],
  })
}

// ---------- 树表工具 ----------
function nodeTargetText(node: StatsTreeNode): string {
  return formatTargetValue(node.target, node.statCaliber ?? ('INCREMENT' as StatCaliber))
}

function nodeActualText(node: StatsTreeNode): string {
  const value = node.statCaliber === 'GROWTH' ? node.actualTotal : node.actualValue
  if (value == null) return EMPTY_DISPLAY
  if (node.statCaliber && isPercentCaliber(node.statCaliber)) return formatPercent(value)
  return formatNumber(value)
}

function trendYearActualText(item: TrendYearItem, caliber: StatCaliber | null): string {
  return formatTargetValue(caliber === 'GROWTH' ? item.actualTotal : item.actualValue, caliber)
}

/** 下钻树行颜色：A 白底、B 浅绿。 */
function treeRowClass({ row }: { row: LazyStatsNode }): string {
  return row.level === 'B' ? 'stats-row-b' : ''
}

function treeCardClass(row: StatsTreeNode): string {
  return row.level === 'B' ? 'tree-card-b' : ''
}

// ---------- 导出 PDF 报告 ----------
const printing = ref(false)
const generatedAt = ref('')
const yearLabel = computed(() => year.value === null ? '五年累计' : `${year.value} 年`)

/** 整页报告导出：通过浏览器打印生成 PDF，包含 KPI、进度、图表与全部表格 */
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

// ---------- 年度核算结果 ----------
const settlementVisible = ref(false)
const settlementYear = ref<number | null>(null)
const settlement = ref<YearSettlement | null>(null)
const settlementLoading = ref(false)

function openSettlement(): void {
  settlementYear.value = year.value ?? plan.value?.years?.find((y) => y.status === 'LOCKED')?.year ?? null
  settlement.value = null
  settlementVisible.value = true
  if (settlementYear.value != null) loadSettlement()
}

async function loadSettlement(): Promise<void> {
  if (!planId.value || settlementYear.value == null) return
  settlementLoading.value = true
  try {
    settlement.value = await getYearSettlement(planId.value, settlementYear.value)
  } catch {
    settlement.value = null
    ElMessage.warning(`${settlementYear.value} 年度暂无核算结果（可能尚未核算）`)
  } finally {
    settlementLoading.value = false
  }
}

function settlementItemTarget(item: { target: number | boolean | null }): string {
  // 核算明细未带口径字段，按原值展示（比率类为小数）
  return formatTargetValue(item.target, 'INCREMENT' as StatCaliber)
}

/** 核算汇总由明细行计算（后端不单独返回 summary 块） */
const settlementSummary = computed(() => {
  const items = settlement.value?.items ?? []
  return {
    total: items.length,
    achieved: items.filter((i) => i.isAchieved === true).length,
    notAchieved: items.filter((i) => i.isAchieved === false).length,
    makeupPending: items.filter((i) => i.makeupStatus === 'PENDING').length,
    madeUp: items.filter((i) => i.makeupStatus === 'DONE').length,
  }
})

onBeforeUnmount(() => {
  if (chartFrame !== null) cancelAnimationFrame(chartFrame)
})

// ---------- 初始化 ----------
onMounted(async () => {
  loading.value = true
  try {
    plans.value = await listPlans()
    if (!planId.value && plans.value.length > 0) {
      const current = getCurrentPlan()
      const fallback = plans.value.find((p) => p.id === current?.id)
        ?? plans.value.find((p) => p.status === 'ACTIVE')
        ?? plans.value[0]
      planId.value = fallback.id
    }
    if (!planId.value) return
    syncQuery()
    await loadPlanContext()
    await loadStats()
  } finally {
    loading.value = false
  }
})

useAutoRefresh(async () => {
  await loadPlanContext()
  await loadStats(true)
})
</script>

<template>
  <div v-loading="loading" class="stats-page">
    <div class="print-report-header">
      <div class="print-report-title">福州软件职业技术学院 · 五年计划统计报表</div>
      <div class="print-report-meta">
        <span>{{ planName }}</span>
        <span>{{ yearLabel }}</span>
        <span v-if="generatedAt">生成时间：{{ generatedAt }}</span>
      </div>
    </div>
    <div class="page-header">
      <div>
        <h1 class="page-title">统计报表</h1>
        <p v-if="planName" class="page-subtitle">{{ planName }}</p>
      </div>
    </div>
    <div class="app-action-row header-actions">
      <el-tooltip
        v-if="!isUnitOwnerOnly"
        content="打开打印窗口，选择“另存为 PDF”即可导出整页统计报告"
        placement="bottom"
      >
        <el-button
          type="primary"
          :loading="printing"
          :disabled="!planId"
          @click="exportPdf"
        >
          导出 PDF 报告
        </el-button>
      </el-tooltip>
      <el-button v-if="!isUnitOwnerOnly" @click="openSettlement">年度核算结果</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline class="stats-filter" @submit.prevent>
        <el-form-item label="计划">
          <el-select v-model="planId" class="plan-select" @change="handlePlanChange">
            <el-option v-for="p in plans" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="年度">
          <el-select v-model="yearSelection" class="year-select" @change="handleYearChange">
            <el-option label="五年累计" :value="0" />
            <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年`" :value="y" />
          </el-select>
        </el-form-item>
      </el-form>
    </el-card>

    <template v-if="overview">
      <div class="kpi-row">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">
            {{ formatPercent(year === null ? overview.cumulativeCompletionRate : overview.overallCompletionRate) }}
          </div>
          <div class="kpi-label">
            {{ year === null ? '五年累计完成率' : `${year} 年完成率` }}
            <el-tooltip
              :content="year === null
                ? '五年累计口径：各指标「累计完成 ÷ 五年总量目标」的平均'
                : '所选年度：达标任务数 ÷ 有正目标的任务数'"
              placement="top"
            >
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

      <YearProgressPanel :plan-id="planId" :year="year" />

      <div class="chart-row">
        <el-card shadow="never" class="chart-card">
          <template #header><span class="card-title">执行单元五年累计完成率排名</span></template>
          <div v-if="byUnit.length === 0" class="chart-empty">暂无数据</div>
          <div v-show="byUnit.length > 0" :ref="unitChart.container" class="chart-box" />
        </el-card>
        <el-card shadow="never" class="chart-card">
          <template #header><span class="card-title">执行单元目标与完成情况</span></template>
          <div v-if="detailRows.length === 0" class="chart-empty">暂无数据</div>
          <div v-show="detailRows.length > 0" :ref="comboChart.container" class="chart-box" />
        </el-card>
      </div>

      <el-card shadow="never" class="table-card">
        <template #header>
          <div class="card-header"><span class="card-title">执行单元明细</span></div>
        </template>
        <ResponsiveRecordView>
          <template #table>
            <el-table :data="detailRows" border size="small">
              <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
              <el-table-column label="任务数" width="110" align="right">
                <template #default="{ row }">{{ formatCount(row.taskCount) }}</template>
              </el-table-column>
              <el-table-column label="达标数" width="110" align="right">
                <template #default="{ row }">{{ formatCount(row.achievedCount) }}</template>
              </el-table-column>
              <el-table-column label="完成率（当年）" width="170" align="right">
                <template #default="{ row }">
                  <div class="rate-cell">
                    <el-progress
                      :percentage="progressPercentage(row.completionRate)"
                      :stroke-width="8"
                      :show-text="false"
                      class="rate-bar"
                    />
                    <span>{{ formatPercent(row.completionRate) }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="五年累计完成率" width="170" align="right">
                <template #default="{ row }">
                  <div class="rate-cell">
                    <el-progress
                      :percentage="progressPercentage(row.cumulativeRate)"
                      :stroke-width="8"
                      :show-text="false"
                      class="rate-bar"
                    />
                    <span>{{ formatPercent(row.cumulativeRate) }}</span>
                  </div>
                </template>
              </el-table-column>
              <template #empty><el-empty :image-size="80" description="暂无数据" /></template>
            </el-table>
          </template>
          <template #cards>
            <el-empty v-if="detailRows.length === 0" :image-size="80" description="暂无数据" />
            <div v-else class="responsive-card-list">
              <article v-for="row in detailRows" :key="row.key" class="responsive-card">
                <div class="responsive-card__header">
                  <div class="responsive-card__title">{{ row.name }}</div>
                </div>
                <div class="responsive-card__fields stats-card-fields">
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">任务数</span>
                    {{ formatCount(row.taskCount) }}
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">达标数</span>
                    {{ formatCount(row.achievedCount) }}
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">完成率（当年）</span>
                    <div class="rate-cell mobile-rate">
                      <el-progress
                        :percentage="progressPercentage(row.completionRate)"
                        :stroke-width="8"
                        :show-text="false"
                        class="rate-bar"
                      />
                      <span>{{ formatPercent(row.completionRate) }}</span>
                    </div>
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">五年累计完成率</span>
                    <div class="rate-cell mobile-rate">
                      <el-progress
                        :percentage="progressPercentage(row.cumulativeRate)"
                        :stroke-width="8"
                        :show-text="false"
                        class="rate-bar"
                      />
                      <span>{{ formatPercent(row.cumulativeRate) }}</span>
                    </div>
                  </div>
                </div>
              </article>
            </div>
          </template>
        </ResponsiveRecordView>
      </el-card>

      <el-card shadow="never" class="table-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">指标下钻（指标 → 学院任务）</span>
          </div>
        </template>
        <ResponsiveRecordView>
          <template #table>
            <el-table
              ref="statsTableRef"
              :data="lazyTree"
              row-key="id"
              border
              size="small"
              lazy
              :load="loadChildren"
              :row-class-name="treeRowClass"
              :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
              @expand-change="handleExpandChange"
            >
          <el-table-column label="名称" min-width="280">
            <template #default="{ row }">
              <span class="node-code">{{ row.code }}</span>
              {{ row.name }}
              <span v-if="row.unit" class="node-unit">（{{ row.unit.name }}）</span>
              <el-tag v-if="row.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="层级" width="90" align="center">
            <template #default="{ row }">
              <StatusTag kind="IndicatorLevel" :value="row.level" />
            </template>
          </el-table-column>
          <el-table-column label="性质" width="90" align="center">
            <template #default="{ row }">
              <StatusTag v-if="row.nature" kind="IndicatorNature" :value="row.nature" />
              <span v-else class="empty-text">—</span>
            </template>
          </el-table-column>
          <el-table-column label="口径" width="90" align="center">
            <template #default="{ row }">
              <StatusTag v-if="row.statCaliber" kind="StatCaliber" :value="row.statCaliber" />
              <span v-else class="empty-text">—</span>
            </template>
          </el-table-column>
          <el-table-column label="本年目标" width="120" align="right">
            <template #default="{ row }">{{ nodeTargetText(row) }}</template>
          </el-table-column>
          <el-table-column label="本年完成" width="120" align="right">
            <template #default="{ row }">{{ nodeActualText(row) }}</template>
          </el-table-column>
          <el-table-column label="当年完成率" width="140" align="right">
            <template #default="{ row }">
              <div class="rate-cell">
                <el-progress
                  :percentage="progressPercentage(row.completionRate)"
                  :stroke-width="8"
                  :show-text="false"
                  class="rate-bar"
                />
                <span>{{ formatPercent(row.completionRate) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="五年累计进度" width="160" align="right">
            <template #default="{ row }">
              <div class="rate-cell">
                <el-progress
                  :percentage="progressPercentage(row.cumulativeRate)"
                  :stroke-width="8"
                  :show-text="false"
                  class="rate-bar"
                />
                <span>{{ formatPercent(row.cumulativeRate ?? null) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.level !== 'B'"
                link
                type="primary"
                size="small"
                @click="openIndicatorDetail(row.id)"
              >
                详情
              </el-button>
            </template>
          </el-table-column>
            </el-table>
          </template>
          <template #cards>
            <el-empty v-if="mobileTreeRows.length === 0" :image-size="80" description="暂无数据" />
            <div v-else class="responsive-card-list">
              <article
                v-for="row in mobileTreeRows"
                :key="row.id"
                class="responsive-card tree-card"
                :class="treeCardClass(row)"
                :style="{ marginLeft: `${row.depth * 16}px` }"
              >
                <div class="responsive-card__header">
                  <div class="responsive-card__title">
                    <span class="node-code">{{ row.code }}</span>
                    {{ row.name }}
                    <span v-if="row.unit" class="node-unit">（{{ row.unit.name }}）</span>
                  </div>
                </div>
                <el-tag v-if="row.isHistorical" class="tree-card-history" type="info" size="small" effect="plain">
                  历史只读
                </el-tag>
                <div class="responsive-card__fields stats-card-fields">
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">层级</span>
                    <StatusTag kind="IndicatorLevel" :value="row.level" />
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">性质</span>
                    <StatusTag v-if="row.nature" kind="IndicatorNature" :value="row.nature" />
                    <span v-else class="empty-text">—</span>
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">口径</span>
                    <StatusTag v-if="row.statCaliber" kind="StatCaliber" :value="row.statCaliber" />
                    <span v-else class="empty-text">—</span>
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">本年目标</span>
                    {{ nodeTargetText(row) }}
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">本年完成</span>
                    {{ nodeActualText(row) }}
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">当年完成率</span>
                    <div class="rate-cell mobile-rate">
                      <el-progress
                        :percentage="progressPercentage(row.completionRate)"
                        :stroke-width="8"
                        :show-text="false"
                        class="rate-bar"
                      />
                      <span>{{ formatPercent(row.completionRate) }}</span>
                    </div>
                  </div>
                  <div class="responsive-card__field">
                    <span class="responsive-card__field-label">五年累计进度</span>
                    <div class="rate-cell mobile-rate">
                      <el-progress
                        :percentage="progressPercentage(row.cumulativeRate)"
                        :stroke-width="8"
                        :show-text="false"
                        class="rate-bar"
                      />
                      <span>{{ formatPercent(row.cumulativeRate ?? null) }}</span>
                    </div>
                  </div>
                  <div v-if="row.yearStatus" class="responsive-card__field">
                    <span class="responsive-card__field-label">年度状态</span>
                    <StatusTag kind="TaskYearStatus" :value="row.yearStatus" />
                  </div>
                </div>
                <div
                  v-if="row.children?.length || row.level !== 'B'"
                  class="responsive-card__actions"
                >
                  <el-button
                    v-if="row.children?.length"
                    link
                    type="primary"
                    :aria-expanded="expandedIds.has(row.id)"
                    @click="handleExpandChange(row, !expandedIds.has(row.id))"
                  >
                    {{ expandedIds.has(row.id) ? '收起' : '展开' }}
                  </el-button>
                  <el-button
                    v-if="row.level !== 'B'"
                    link
                    type="primary"
                    @click="openIndicatorDetail(row.id)"
                  >
                    详情
                  </el-button>
                </div>
              </article>
            </div>
          </template>
        </ResponsiveRecordView>
      </el-card>

    </template>

    <el-empty v-else-if="!loading" :description="emptyDescription" />

    <el-dialog v-model="settlementVisible" title="年度核算结果" width="min(880px, calc(100vw - 32px))">
      <el-form inline @submit.prevent>
        <el-form-item label="年度">
          <el-select v-model="settlementYear" style="width: 140px" @change="loadSettlement">
            <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年`" :value="y" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-loading="settlementLoading">
        <template v-if="settlement">
          <div class="settle-summary">
            <span>核算时间：{{ settlement.settledAt ?? '—' }}</span>
            <span v-if="settlement.settledBy">核算人：{{ settlement.settledBy.name }}</span>
            <el-tag type="success" effect="plain">达标 {{ settlementSummary.achieved }}</el-tag>
            <el-tag type="danger" effect="plain">未达标 {{ settlementSummary.notAchieved }}</el-tag>
            <el-tag type="warning" effect="plain">待补做 {{ settlementSummary.makeupPending }}</el-tag>
            <el-tag type="info" effect="plain">已补做 {{ settlementSummary.madeUp }}</el-tag>
            <span class="settle-total">共 {{ settlementSummary.total }} 条任务</span>
          </div>
          <el-table :data="settlement.items" border size="small" max-height="420">
            <el-table-column prop="taskCode" label="任务编号" width="130" />
            <el-table-column label="指标" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.indicator?.name ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="执行单元" width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.unit?.name ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="年度目标" width="110" align="right">
              <template #default="{ row }">{{ settlementItemTarget(row) }}</template>
            </el-table-column>
            <el-table-column label="完成值" width="110" align="right">
              <template #default="{ row }">{{ formatNumber(row.actualValue) }}</template>
            </el-table-column>
            <el-table-column label="完成率" width="100" align="right">
              <template #default="{ row }">{{ formatPercent(row.completionRate) }}</template>
            </el-table-column>
            <el-table-column label="年度状态" width="110" align="center">
              <template #default="{ row }">
                <StatusTag kind="TaskYearStatus" :value="row.yearStatus" />
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!settlementLoading" description="选择年度查询核算结果" :image-size="70" />
      </div>
    </el-dialog>

    <el-dialog
      v-model="trendVisible"
      title="指标统计详情"
      width="min(760px, calc(100vw - 32px))"
      align-center
      class="indicator-trend-dialog"
      destroy-on-close
    >
      <div v-loading="trendLoading" class="trend-dialog-body">
        <template v-if="indicatorTrend">
          <div class="trend-heading">
            <div><span class="mono">{{ indicatorTrend.code }}</span><h2>{{ indicatorTrend.name }}</h2></div>
            <StatusTag v-if="indicatorTrend.statCaliber" kind="StatCaliber" :value="indicatorTrend.statCaliber" />
          </div>
          <div class="trend-summary">
            <div><span>五年总目标</span><strong>{{ formatTargetValue(indicatorTrend.totalTarget, indicatorTrend.statCaliber) }}</strong></div>
            <div><span>累计完成</span><strong>{{ formatTargetValue(indicatorTrend.cumulativeActual, indicatorTrend.statCaliber) }}</strong></div>
            <div><span>累计进度</span><strong class="primary-value">{{ formatPercent(indicatorTrend.cumulativeRate ?? null) }}</strong></div>
          </div>
          <el-table :data="indicatorTrend.years" border size="small" class="trend-table">
            <el-table-column prop="year" label="年度" width="76" align="center" />
            <el-table-column label="目标" min-width="110" align="right">
              <template #default="{ row }">{{ formatTargetValue(row.target, indicatorTrend.statCaliber) }}</template>
            </el-table-column>
            <el-table-column label="完成值（审核通过）" min-width="135" align="right">
              <template #default="{ row }">{{ trendYearActualText(row, indicatorTrend.statCaliber) }}</template>
            </el-table-column>
            <el-table-column label="完成率" width="100" align="right">
              <template #default="{ row }">{{ formatPercent(row.completionRate) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }"><StatusTag v-if="row.achieveStatus" kind="AchieveStatus" :value="row.achieveStatus" /><span v-else>—</span></template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!trendLoading" description="暂未获取到指标趋势数据" :image-size="80" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.stats-page {
  min-width: 0;
  max-width: 100%;
}

/* 仅在 PDF 打印时显示的报表抬头 */
.print-report-header {
  display: none;
}

.print-report-title {
  color: #17233c;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.print-report-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 6px;
  color: #606266;
  font-size: 12px;
  line-height: 18px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.page-header > div {
  min-width: 0;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.filter-card,
.table-card {
  min-width: 0;
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.stats-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 0 var(--space-2);
}

.stats-filter :deep(.el-form-item) {
  min-width: 0;
  margin-right: 0;
}

.plan-select {
  width: 280px;
  max-width: 100%;
}

.year-select {
  width: 140px;
  max-width: 100%;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.kpi-card {
  min-width: 0;
  border-radius: var(--radius-card);
  text-align: center;
}

.kpi-value {
  font-family: var(--app-font-number);
  font-size: 24px;
  font-weight: 600;
  line-height: 32px;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
}

.kpi-value.is-good {
  color: var(--el-color-success);
}

.kpi-value.is-bad {
  color: var(--el-color-danger);
}

.kpi-total {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.kpi-label {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kpi-sub {
  font-size: 12px;
}

.text-danger {
  color: var(--el-color-danger);
}

.text-teal {
  color: var(--app-color-teal);
}

.chart-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.chart-card {
  min-width: 0;
  border-radius: var(--radius-card);
}

.card-title {
  min-width: 0;
  font-size: 16px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.chart-box {
  width: 100%;
  height: 320px;
}

.chart-empty {
  height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.node-code {
  margin-right: var(--space-1);
  font-family: var(--app-font-mono);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.node-unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rate-cell {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  justify-content: flex-end;
  font-variant-numeric: tabular-nums;
}

.rate-bar {
  width: 56px;
  flex-shrink: 0;
}

.mobile-rate {
  justify-content: flex-start;
  margin-top: var(--space-1);
}

.tree-card-history {
  margin-top: var(--space-2);
}

/* ---------- 下钻树行颜色：A 白底、B 浅绿 ---------- */
:deep(.stats-row-b > td) {
  background-color: var(--el-color-success-light-9) !important;
}

:deep(.stats-row-b > td:first-child) {
  box-shadow: inset 3px 0 0 var(--el-color-success);
}

:deep(.stats-row-b:hover > td) {
  background-color: var(--el-color-success-light-8) !important;
}

.tree-card-b {
  background: var(--el-color-success-light-9);
  border-left-color: var(--el-color-success);
}

.empty-text {
  color: #a8abb2;
}

.settle-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  font-variant-numeric: tabular-nums;
}

.settle-total {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

@media (max-width: 1440px) {
  .kpi-row {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1280px) {
  .chart-row {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (min-width: 481px) and (max-width: 768px) {
  .stats-card-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
  }

  .header-actions {
    width: 100%;
  }

  .header-actions > .el-button {
    flex: 1;
    margin-left: 0;
  }

  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-2);
  }

  .chart-box,
  .chart-empty {
    height: 280px;
  }

  .settle-total {
    width: 100%;
    margin-left: 0;
  }
}

.trend-dialog-body { min-height: 240px; }
.trend-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.trend-heading .mono { color: #2B5BC0; font-weight: 600; }
.trend-heading h2 { margin: 5px 0 0; font-size: 18px; line-height: 1.5; }
.trend-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; margin-bottom: 18px; background: #E4E9F2; border: 1px solid #E4E9F2; }
.trend-summary > div { display: flex; flex-direction: column; gap: 7px; padding: 14px; background: #F7F9FC; }
.trend-summary span { color: #606266; font-size: 12px; }
.trend-summary strong { font-size: 20px; font-variant-numeric: tabular-nums; }
.trend-summary .primary-value { color: #2B5BC0; }
.trend-table { width: 100%; }

@media (max-width: 600px) {
  .stats-filter :deep(.el-form-item) {
    width: 100%;
    margin-bottom: var(--space-3);
  }

  .stats-filter :deep(.el-form-item__label) {
    height: auto;
    margin-bottom: var(--space-1);
    line-height: 20px;
  }

  .stats-filter :deep(.el-form-item__content),
  .plan-select,
  .year-select {
    width: 100%;
  }

  .kpi-card :deep(.el-card__body) {
    padding: var(--space-3) var(--space-2);
  }

  .kpi-value {
    font-size: 22px;
    line-height: 30px;
  }

  .trend-summary { grid-template-columns: 1fr; }
  .trend-summary > div { padding: 10px 12px; }
}

</style>

<style>
/* PDF 报告打印：隐藏导航与操作区，完整输出标题、KPI、进度、图表和表格 */
@media print {
  @page {
    size: A4 landscape;
    margin: 10mm;
  }

  body.stats-print-mode .app-header,
  body.stats-print-mode .app-aside,
  body.stats-print-mode .plan-toolbar,
  body.stats-print-mode .app-action-row,
  body.stats-print-mode .filter-card {
    display: none !important;
  }

  body.stats-print-mode .app-layout,
  body.stats-print-mode .app-body,
  body.stats-print-mode .app-right,
  body.stats-print-mode .app-main {
    display: block !important;
    height: auto !important;
    min-height: 0 !important;
    overflow: visible !important;
    padding: 0 !important;
  }

  body.stats-print-mode .stats-page {
    width: 100% !important;
    max-width: none !important;
  }

  body.stats-print-mode .print-report-header {
    display: block !important;
    margin-bottom: 12px;
    padding-bottom: 8px;
    border-bottom: 2px solid #2b5bc0;
  }

  body.stats-print-mode .kpi-row {
    grid-template-columns: repeat(6, minmax(0, 1fr)) !important;
    gap: 8px !important;
  }

  body.stats-print-mode .chart-row {
    grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
  }

  body.stats-print-mode .el-card {
    border: 1px solid #dcdfe6 !important;
    box-shadow: none !important;
  }

  body.stats-print-mode .chart-card,
  body.stats-print-mode .year-progress-panel {
    break-inside: avoid;
  }

  body.stats-print-mode .el-table {
    font-size: 11px !important;
  }

  body.stats-print-mode .el-table__inner-wrapper::before {
    display: none !important;
  }

  body.stats-print-mode .el-table__body-wrapper,
  body.stats-print-mode .el-scrollbar__wrap {
    overflow: visible !important;
  }

  body.stats-print-mode .chart-box canvas {
    max-width: 100% !important;
    height: auto !important;
  }
}
</style>
