<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElNotification } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import { listPlans, getPlan } from '@/api/plan'
import {
  getIndicatorTrend,
  getStatsByIndicator,
  getStatsByUnit,
  getStatsOverview,
  getStatsTree,
} from '@/api/stats'
import { getYearSettlement } from '@/api/settlement'
import { createExport, pollExport } from '@/api/export'
import {
  CHART_ACTUAL_COLOR,
  CHART_TARGET_COLOR,
  CHART_TIME_PROGRESS_COLOR,
  useEChart,
} from '@/utils/chart'
import { newIdempotencyKey } from '@/utils/idempotency'
import { ApiError } from '@/utils/request'
import { enumLabel } from '@/utils/enumLabels'
import { EMPTY_DISPLAY, formatCount, formatNumber, formatPercent } from '@/utils/format'
import { formatTargetValue, isPercentCaliber } from '@/utils/indicator'
import { progressPercentage } from '@/utils/task'
import type { PlanCard, PlanDetail } from '@/types/plan'
import type { ExportType } from '@/types/export'
import type {
  IndicatorStat,
  StatsOverview,
  StatsTreeNode,
  TrendYearItem,
  UnitStatItem,
  YearSettlement,
} from '@/types/stats'
import type { StatCaliber } from '@/types/enums'

const route = useRoute()
const router = useRouter()

const plans = ref<PlanCard[]>([])
const planId = ref<string>((route.query.planId as string) || '')
const plan = ref<PlanDetail | null>(null)
/** null 表示五年累计 */
const year = ref<number | null>(route.query.year ? Number(route.query.year) : null)

const loading = ref(false)
const overview = ref<StatsOverview | null>(null)
const byUnit = ref<UnitStatItem[]>([])
const byIndicator = ref<IndicatorStat[]>([])
const tree = ref<StatsTreeNode[]>([])

type LazyStatsNode = Omit<StatsTreeNode, 'children'> & {
  children: LazyStatsNode[]
  hasChildren: boolean
}

const statsTableRef = ref<{ updateKeyChildren: (id: string, children: LazyStatsNode[]) => void } | null>(null)
const expandedIds = new Set<string>()

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

function loadChildren(row: LazyStatsNode, _node: unknown, resolve: (children: LazyStatsNode[]) => void): void {
  resolve(childrenById.value[row.id] ?? [])
}

function handleExpandChange(row: LazyStatsNode, expanded: boolean): void {
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

// ---------- 统计维度（UI 规范 P11：执行单元 / A 级指标 / 指标层级 / 指标性质 / 主体） ----------
type StatsDimension = 'UNIT' | 'INDICATOR' | 'LEVEL' | 'NATURE' | 'SUBJECT'

const dimension = ref<StatsDimension>('UNIT')

const dimensionOptions: { value: StatsDimension; label: string }[] = [
  { value: 'UNIT', label: '执行单元' },
  { value: 'INDICATOR', label: 'A 级指标' },
  { value: 'LEVEL', label: '指标层级' },
  { value: 'NATURE', label: '指标性质' },
  { value: 'SUBJECT', label: '主体' },
]

interface DimRow {
  key: string
  name: string
  taskCount: number
  achievedCount: number | null
  completionRate: number | null
  /** 五年累计完成率（UNIT/INDICATOR 来自后端；LEVEL/NATURE 由树节点前端聚合） */
  cumulativeRate: number | null
}

/** 指标层级 / 性质维度由 stats/tree 前端聚合（主体维度暂无聚合接口，用下钻树演示过滤） */
const dimensionNote = computed(() => {
  if (dimension.value === 'SUBJECT') {
    return '主体维度暂无后端聚合接口，请在下方下钻树中按执行单元查看'
  }
  if (dimension.value === 'LEVEL' || dimension.value === 'NATURE') {
    return '该维度由下钻树（stats/tree）在前端聚合'
  }
  return ''
})

const detailRows = computed<DimRow[]>(() => {
  if (dimension.value === 'UNIT') {
    return byUnit.value.map((item) => ({
      key: item.unit?.id ?? item.unit?.name ?? '',
      name: item.unit?.name ?? EMPTY_DISPLAY,
      taskCount: item.taskCount,
      achievedCount: item.achievedCount,
      completionRate: item.completionRate,
      cumulativeRate: item.cumulativeRate,
    }))
  }
  if (dimension.value === 'INDICATOR') {
    return byIndicator.value.map((item) => ({
      key: item.indicatorId,
      name: item.name,
      taskCount: item.taskCount,
      achievedCount: item.achievedCount,
      completionRate: item.completionRate,
      cumulativeRate: item.cumulativeRate,
    }))
  }
  if (dimension.value === 'LEVEL' || dimension.value === 'NATURE') {
    const grouped = new Map<string, { name: string; taskCount: number; achievedCount: number; cumRates: number[] }>()
    const walk = (nodes: StatsTreeNode[]): void => {
      for (const node of nodes) {
        if (dimension.value === 'LEVEL') {
          const key = node.level
          const entry = grouped.get(key) ?? { name: enumLabel('IndicatorLevel', key), taskCount: 0, achievedCount: 0, cumRates: [] }
          entry.taskCount += 1
          if (node.achieveStatus === 'ACHIEVED' || node.achieveStatus === 'EXCEEDED') {
            entry.achievedCount += 1
          }
          if (typeof node.cumulativeRate === 'number') entry.cumRates.push(node.cumulativeRate)
          grouped.set(key, entry)
        } else if (node.level === 'B') {
          const key = node.nature ?? 'UNKNOWN'
          const name = node.nature ? enumLabel('IndicatorNature', node.nature) : '未标注'
          const entry = grouped.get(key) ?? { name, taskCount: 0, achievedCount: 0, cumRates: [] }
          entry.taskCount += 1
          if (node.achieveStatus === 'ACHIEVED' || node.achieveStatus === 'EXCEEDED') {
            entry.achievedCount += 1
          }
          if (typeof node.cumulativeRate === 'number') entry.cumRates.push(node.cumulativeRate)
          grouped.set(key, entry)
        }
        if (node.children?.length) walk(node.children)
      }
    }
    walk(tree.value)
    return [...grouped.entries()].map(([key, entry]) => ({
      key,
      name: entry.name,
      taskCount: entry.taskCount,
      achievedCount: entry.achievedCount,
      completionRate: entry.taskCount > 0 ? entry.achievedCount / entry.taskCount : null,
      cumulativeRate: entry.cumRates.length > 0
        ? entry.cumRates.reduce((a, b) => a + b, 0) / entry.cumRates.length
        : null,
    }))
  }
  return []
})

async function loadPlanContext(): Promise<void> {
  if (!planId.value) return
  plan.value = await getPlan(planId.value)
}

let chartFrame: number | null = null

async function loadStats(): Promise<void> {
  if (!planId.value) return
  loading.value = true
  try {
    const y = year.value ?? undefined
    const [o, u, d, t] = await Promise.all([
      getStatsOverview(planId.value, y),
      getStatsByUnit(planId.value, y),
      getStatsByIndicator(planId.value, y),
      getStatsTree(planId.value, y),
    ])
    overview.value = o
    byUnit.value = u
    byIndicator.value = d
    tree.value = t
    await syncExpandedNodes()
    if (chartFrame !== null) cancelAnimationFrame(chartFrame)
    chartFrame = requestAnimationFrame(() => {
      chartFrame = null
      renderUnitChart()
      renderComboChart()
    })
  } finally {
    loading.value = false
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
  year.value = null
  syncQuery()
  trendIndicator.value = null
  trendData.value = []
  loadPlanContext().then(loadStats)
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

// ---------- 目标 vs 完成（柱）+ 完成率（折线，右轴）组合图（按当前统计维度） ----------
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

watch(dimension, async () => {
  await nextTick()
  renderComboChart()
})

// ---------- 指标趋势 ----------
const trendChart = useEChart()
const trendIndicator = ref<{ id: string; code: string; name: string } | null>(null)
const trendData = ref<TrendYearItem[]>([])
const trendLoading = ref(false)

async function showTrend(node: StatsTreeNode): Promise<void> {
  if (node.level === 'B' || !node.statCaliber) return
  trendIndicator.value = { id: node.id, code: node.code, name: node.name }
  trendLoading.value = true
  try {
    const trend = await getIndicatorTrend(node.id)
    trendData.value = trend.years ?? []
    await nextTick()
    renderTrendChart()
  } finally {
    trendLoading.value = false
  }
}

function renderTrendChart(): void {
  const points = trendData.value
  trendChart.setOption({
    legend: { top: 0 },
    grid: { left: 8, right: 24, top: 32, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: points.map((p) => `${p.year}`) },
    yAxis: { type: 'value' },
    series: [
      {
        name: '目标',
        type: 'line',
        smooth: true,
        symbolSize: 6,
        lineStyle: { color: '#BFCEEC', type: 'dashed' },
        itemStyle: { color: '#BFCEEC' },
        data: points.map((p) => (typeof p.target === 'number' ? p.target : null)),
      },
      {
        name: '实际',
        type: 'line',
        smooth: true,
        symbolSize: 6,
        lineStyle: { color: '#2B5BC0', width: 3 },
        itemStyle: { color: '#2B5BC0' },
        data: points.map((p) => p.actualValue),
      },
    ],
  })
}

// ---------- 树表工具 ----------
function nodeTargetText(node: StatsTreeNode): string {
  return formatTargetValue(node.target, node.statCaliber ?? ('INCREMENT' as StatCaliber))
}

function nodeActualText(node: StatsTreeNode): string {
  if (node.actualValue == null) return EMPTY_DISPLAY
  if (node.statCaliber && isPercentCaliber(node.statCaliber)) return formatPercent(node.actualValue)
  return formatNumber(node.actualValue)
}

// ---------- 导出 ----------
const exporting = ref(false)

async function handleExport(type: ExportType, params: Record<string, string | number | undefined>): Promise<void> {
  exporting.value = true
  const notification = ElNotification({
    title: '导出 Excel',
    message: '导出任务已创建，排队中 → 生成中…',
    type: 'info',
    duration: 0,
  })
  try {
    const { exportId } = await createExport({ type, params }, newIdempotencyKey())
    const job = await pollExport(exportId)
    notification.close()
    if (job.status === 'SUCCEEDED' && job.downloadUrl) {
      window.open(job.downloadUrl, '_blank')
      ElNotification({ title: '导出 Excel', message: '导出完成，已开始下载', type: 'success' })
    } else {
      ElNotification({
        title: '导出失败',
        message: job.errorMessage ?? '导出任务失败，请稍后重试',
        type: 'error',
      })
    }
  } catch (error) {
    notification.close()
    // 导出失败通知带 requestId（UI 规范 7.10 / 7.11）
    const message =
      error instanceof ApiError && error.requestId
        ? `${error.message}（请求编号：${error.requestId}）`
        : error instanceof Error
          ? error.message
          : '导出任务失败，请稍后重试'
    ElNotification({ title: '导出失败', message, type: 'error' })
  } finally {
    exporting.value = false
  }
}

function exportStats(): void {
  if (!planId.value) return
  handleExport('STATS_REPORT', { planId: planId.value, year: year.value ?? undefined })
}

// ---------- 年度核算结果 ----------
const settlementVisible = ref(false)
const settlementYear = ref<number | null>(null)
const settlement = ref<YearSettlement | null>(null)
const settlementLoading = ref(false)

function openSettlement(): void {
  settlementYear.value = year.value ?? plan.value?.years.find((y) => y.status === 'LOCKED')?.year ?? null
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
      planId.value = (plans.value.find((p) => p.status === 'ACTIVE') ?? plans.value[0]).id
    }
    if (!planId.value) return
    syncQuery()
    await loadPlanContext()
    await loadStats()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h1 class="page-title">统计报表</h1>
        <p v-if="planName" class="page-subtitle">{{ planName }}</p>
      </div>
      <div class="header-actions">
        <el-button @click="openSettlement">年度核算结果</el-button>
        <el-button type="primary" :loading="exporting" :disabled="!planId" @click="exportStats">
          导出 Excel
        </el-button>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="计划">
          <el-select v-model="planId" style="width: 280px" @change="handlePlanChange">
            <el-option v-for="p in plans" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="年度">
          <el-select v-model="year" style="width: 140px" @change="handleYearChange">
            <el-option label="五年累计" :value="null" />
            <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年`" :value="y" />
          </el-select>
        </el-form-item>
        <el-form-item label="统计维度">
          <el-radio-group v-model="dimension" size="small">
            <el-radio-button
              v-for="option in dimensionOptions"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
    </el-card>

    <template v-if="overview">
      <div class="kpi-row">
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

      <div class="chart-row">
        <el-card shadow="never" class="chart-card">
          <template #header><span class="card-title">执行单元五年累计完成率排名</span></template>
          <div v-if="byUnit.length === 0" class="chart-empty">暂无数据</div>
          <div v-show="byUnit.length > 0" :ref="unitChart.container" class="chart-box" />
        </el-card>
        <el-card shadow="never" class="chart-card">
          <template #header>
            <span class="card-title">
              目标 vs 完成（按{{ dimensionOptions.find((o) => o.value === dimension)?.label }}）
            </span>
          </template>
          <div v-if="detailRows.length === 0" class="chart-empty">
            {{ dimensionNote || '暂无数据' }}
          </div>
          <div v-show="detailRows.length > 0" :ref="comboChart.container" class="chart-box" />
        </el-card>
      </div>

      <el-card shadow="never" class="table-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">明细表（按{{ dimensionOptions.find((o) => o.value === dimension)?.label }}）</span>
            <span v-if="dimensionNote" class="card-tip">{{ dimensionNote }}</span>
          </div>
        </template>
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
          <template #empty>
            <el-empty :image-size="80" :description="dimensionNote || '暂无数据'" />
          </template>
        </el-table>
      </el-card>

      <el-card shadow="never" class="table-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">指标下钻（A → A+ → B）</span>
            <span class="card-tip">点击「趋势」查看指标 2026–2030 目标与实际对比</span>
          </div>
        </template>
        <el-table
          ref="statsTableRef"
          :data="lazyTree"
          row-key="id"
          border
          size="small"
          lazy
          :load="loadChildren"
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
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <StatusTag v-if="row.achieveStatus" kind="AchieveStatus" :value="row.achieveStatus" />
              <span v-else class="empty-text">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" align="center" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.level !== 'B' && row.statCaliber" link type="primary" size="small" @click="showTrend(row)">
                趋势
              </el-button>
              <el-button
                v-if="row.level !== 'B'"
                link
                type="primary"
                size="small"
                @click="router.push(`/indicators/${row.id}`)"
              >
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="trendIndicator" v-loading="trendLoading" shadow="never" class="table-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">
              指标趋势：{{ trendIndicator.code }} {{ trendIndicator.name }}
            </span>
            <el-button link type="info" size="small" @click="trendIndicator = null">收起</el-button>
          </div>
        </template>
        <div v-if="trendData.length === 0 && !trendLoading" class="chart-empty">暂无趋势数据</div>
        <div v-show="trendData.length > 0" :ref="trendChart.container" class="chart-box" />
      </el-card>
    </template>

    <el-empty v-else-if="!loading" description="暂无统计数据（计划未选择或接口未就绪）" />

    <el-dialog v-model="settlementVisible" title="年度核算结果" width="880px">
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
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
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
}

.header-actions {
  display: flex;
  gap: var(--space-2);
}

.filter-card,
.table-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.kpi-card {
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
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.chart-card {
  border-radius: var(--radius-card);
}

.card-title {
  font-size: 16px;
  font-weight: 600;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
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
}

.empty-text {
  color: #a8abb2;
}

.settle-summary {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  font-variant-numeric: tabular-nums;
}

.settle-total {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
