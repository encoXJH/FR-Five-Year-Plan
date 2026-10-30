<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'
import { errorMessage, executionApi } from '@/api'
import type { ConstructionPlan, DashboardMilestoneStats, LeaderDashboard, LeaderDashboardCollege, LeaderDashboardDirection, LeaderDashboardIndicator, LeaderDashboardYear } from '@/types/api'
import { statusLabel } from '@/utils/dictionaries'
import { formatNumber, percentWidth } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import PlanSelect from '@/components/PlanSelect.vue'
import RemoteState from '@/components/RemoteState.vue'
import StatusTag from '@/components/StatusTag.vue'

type Dimension = 'COLLEGE' | 'INDICATOR' | 'DIRECTION' | 'TYPE'
interface TypeRow extends DashboardMilestoneStats {
  indicator_type_code: string
  indicator_count: number
  task_count: number
  progress_percent: number
}
interface BarPoint extends LeaderDashboardCollege {
  center: number
  barWidth: number
  targetX: number
  completedX: number
  targetY: number
  completedY: number
  targetHeight: number
  completedHeight: number
  rateY: number
}

const dimensions: Array<{ value: Dimension; label: string }> = [
  { value: 'COLLEGE', label: '执行单元' },
  { value: 'INDICATOR', label: '指标' },
  { value: 'DIRECTION', label: '方向' },
  { value: 'TYPE', label: '指标性质' },
]
const route = useRoute()
const router = useRouter()
const planId = ref(String(route.query.plan_id || ''))
const plan = ref<ConstructionPlan>()
const dashboard = ref<LeaderDashboard>()
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const directionId = ref('')
const dimension = ref<Dimension>('COLLEGE')

function onPlansLoaded(plans: ConstructionPlan[]) {
  if (!planId.value && plans.length) {
    planId.value = String(plans[0].id)
    router.replace({ query: { plan_id: planId.value } })
    load()
  }
}
async function load() {
  if (!planId.value) { dashboard.value = undefined; return }
  loading.value = true; error.value = ''
  try {
    const data = await executionApi.leaderDashboard(planId.value)
    dashboard.value = data
    plan.value = data.plan
  } catch (cause) {
    dashboard.value = undefined
    error.value = errorMessage(cause)
  } finally { loading.value = false }
}
function changePlan() {
  directionId.value = ''
  keyword.value = ''
  router.replace({ query: { plan_id: planId.value } })
  load()
}
const summary = computed(() => dashboard.value?.summary)
const filteredIndicators = computed(() => {
  const items = dashboard.value?.indicators || []
  const text = keyword.value.trim().toLowerCase()
  return items.filter((item) => (!directionId.value || String(item.direction_id) === directionId.value) && (!text || `${item.indicator_name}${item.indicator_code}`.toLowerCase().includes(text)))
})
const collegeRank = computed(() => [...(dashboard.value?.colleges || [])].sort((left, right) => right.progress_percent - left.progress_percent))
const indicatorStats = computed(() => {
  const items = dashboard.value?.indicators || []
  const achieved = items.filter((item) => item.milestone_count > 0 && item.completed >= item.milestone_count).length
  const inProgress = items.filter((item) => item.task_count > 0 && !(item.milestone_count > 0 && item.completed >= item.milestone_count) && item.unmet === 0).length
  const unmet = items.filter((item) => item.unmet > 0).length
  const notAssigned = items.filter((item) => !item.task_count).length
  return { achieved, inProgress, unmet, notAssigned }
})
const typeRows = computed<TypeRow[]>(() => {
  const groups = new Map<string, TypeRow>()
  for (const item of dashboard.value?.indicators || []) {
    const row = groups.get(item.indicator_type_code) || {
      indicator_type_code: item.indicator_type_code,
      indicator_count: 0,
      task_count: 0,
      milestone_count: 0,
      completed: 0,
      under_review: 0,
      returned: 0,
      unmet: 0,
      pending_report: 0,
      not_started: 0,
      draft: 0,
      progress_percent: 0,
    }
    row.indicator_count += 1
    row.task_count += item.task_count
    row.milestone_count += item.milestone_count
    row.completed += item.completed
    row.under_review += item.under_review
    row.returned += item.returned
    row.unmet += item.unmet
    row.pending_report += item.pending_report
    row.not_started += item.not_started
    row.draft += item.draft
    groups.set(item.indicator_type_code, row)
  }
  return [...groups.values()].map((item) => ({ ...item, progress_percent: item.milestone_count ? Math.round(item.completed / item.milestone_count * 10000) / 100 : 0 }))
})
const dimensionLabel = computed(() => dimensions.find((item) => item.value === dimension.value)?.label || '执行单元')

/* 年度累计折线图 */
const chart = computed(() => {
  const years = dashboard.value?.years || []
  const total = years.reduce((sum, item) => sum + item.milestone_count, 0)
  let completed = 0
  let reviewed = 0
  const points = years.map((item) => {
    completed += item.completed
    reviewed += item.completed + item.unmet
    return {
      year: item.target_year,
      completed,
      reviewed,
      completedRate: total ? (completed / total) * 100 : 0,
      reviewedRate: total ? (reviewed / total) * 100 : 0,
    }
  })
  return { total, points }
})
const CHART = { width: 920, height: 320, top: 28, right: 28, bottom: 46, left: 58 }
const plotWidth = CHART.width - CHART.left - CHART.right
const plotHeight = CHART.height - CHART.top - CHART.bottom
const chartTicks = [0, 25, 50, 75, 100]
function chartX(index: number) {
  const count = chart.value.points.length
  if (count <= 1) return CHART.left + plotWidth / 2
  return CHART.left + (index * plotWidth) / (count - 1)
}
function chartY(rate: number) {
  const clamped = Math.max(0, Math.min(100, rate))
  return CHART.top + plotHeight - (clamped / 100) * plotHeight
}
const completedPolyline = computed(() => chart.value.points.map((point, index) => `${chartX(index)},${chartY(point.completedRate)}`).join(' '))
const reviewedPolyline = computed(() => chart.value.points.map((point, index) => `${chartX(index)},${chartY(point.reviewedRate)}`).join(' '))
const chartAreaPath = computed(() => {
  const points = chart.value.points
  if (!points.length) return ''
  const startX = chartX(0)
  const endX = chartX(points.length - 1)
  const line = points.map((point, index) => `${chartX(index)} ${chartY(point.completedRate)}`).join(' L ')
  return `M ${startX} ${chartY(0)} L ${line} L ${endX} ${chartY(0)} Z`
})
const chartLast = computed(() => chart.value.points[chart.value.points.length - 1])

/* 执行单元目标 vs 完成图 */
const BAR = { width: 560, height: 300, top: 26, right: 48, bottom: 54, left: 42 }
const barPlotWidth = BAR.width - BAR.left - BAR.right
const barPlotHeight = BAR.height - BAR.top - BAR.bottom
const barMax = computed(() => {
  const max = Math.max(1, ...(dashboard.value?.colleges || []).map((item) => item.milestone_count))
  return max <= 5 ? 5 : Math.ceil(max / 5) * 5
})
const barTicks = computed(() => Array.from({ length: 5 }, (_, index) => Math.round(barMax.value * index / 4)))
const barPoints = computed<BarPoint[]>(() => {
  const rows = dashboard.value?.colleges || []
  const slot = barPlotWidth / Math.max(rows.length, 1)
  const barWidth = Math.min(16, slot * 0.26)
  return rows.map((row, index) => {
    const center = BAR.left + slot * (index + 0.5)
    const targetHeight = row.milestone_count / barMax.value * barPlotHeight
    const completedHeight = row.completed / barMax.value * barPlotHeight
    const rateY = BAR.top + barPlotHeight - Math.max(0, Math.min(100, row.progress_percent)) / 100 * barPlotHeight
    return {
      ...row,
      center,
      barWidth,
      targetX: center - barWidth - 2,
      completedX: center + 2,
      targetY: BAR.top + barPlotHeight - targetHeight,
      completedY: BAR.top + barPlotHeight - completedHeight,
      targetHeight,
      completedHeight,
      rateY,
    }
  })
})
const barRateLine = computed(() => barPoints.value.map((point) => `${point.center},${point.rateY}`).join(' '))
function barY(value: number) { return BAR.top + barPlotHeight - (value / barMax.value) * barPlotHeight }
function nameLines(name: string) {
  if (name.length <= 6) return [name]
  if (name.endsWith('学院')) {
    const head = name.slice(0, -2)
    if (head.length <= 6) return [head, '学院']
    const middle = Math.ceil(head.length / 2)
    return [head.slice(0, middle), `${head.slice(middle)}学院`]
  }
  const middle = Math.ceil(name.length / 2)
  return [name.slice(0, middle), name.slice(middle)]
}

function percent(value?: number | string | null) { return percentWidth(value) }
function progressText(item: LeaderDashboardIndicator | LeaderDashboardDirection | LeaderDashboardCollege | LeaderDashboardYear | TypeRow) {
  if (!item.milestone_count) return '未下发'
  if (item.completed >= item.milestone_count) return '已完成'
  if (item.unmet > 0) return '存在未达标'
  if (item.under_review > 0) return '审核中'
  if (item.pending_report > 0) return '待填报'
  return '推进中'
}
function progressTone(item: { milestone_count: number; completed: number; unmet: number }) {
  if (item.milestone_count && item.completed >= item.milestone_count) return 'done'
  if (item.unmet > 0) return 'risk'
  return 'active'
}
function progressValue(item: { progress_percent?: number; completion_percent?: number }) {
  return percent(item.progress_percent ?? item.completion_percent)
}
function amountText(item: LeaderDashboardIndicator) {
  if (item.progress_basis !== 'VALUE' || item.target_value === null || item.target_value === undefined) return ''
  const unit = item.unit_name ? ` ${item.unit_name}` : ''
  return `认定 ${formatNumber(item.recognized_value ?? 0)} / 目标 ${formatNumber(item.target_value)}${unit}`
}
function statusCountsText(counts: Record<string, number>) {
  return Object.entries(counts).map(([key, value]) => `${statusLabel(key)} ${value}`).join(' · ')
}
onMounted(load)
</script>
<template>
  <div class="page-container analytics-page">
    <PageHeader title="数据看板" description="按执行单元、指标和方向汇总完成情况；指标进度按同口径计算，跨指标只统计数量，不对不同单位做简单平均或加总。">
      <template #actions><el-button :disabled="!planId" @click="load"><el-icon><Refresh /></el-icon>刷新数据</el-button></template>
    </PageHeader>
    <div class="analytics-filter">
      <span class="filter-label">计划</span>
      <div class="filter-plan"><PlanSelect v-model="planId" @loaded="onPlansLoaded" @change="changePlan" /></div>
      <span v-if="plan" class="filter-meta"><StatusTag :status="plan.plan_status" /><span>{{ plan.start_year }}—{{ plan.end_year }}</span></span>
      <el-select v-if="dimension === 'INDICATOR'" v-model="directionId" placeholder="全部方向" clearable filterable style="width:190px"><el-option v-for="item in dashboard?.directions || []" :key="item.id" :value="String(item.id)" :label="item.direction_name" /></el-select>
      <el-input v-if="dimension === 'INDICATOR'" v-model="keyword" placeholder="搜索指标名称或代码" clearable style="width:220px"><template #prefix><el-icon><Search /></el-icon></template></el-input>
      <div class="dimension-tabs">
        <button v-for="item in dimensions" :key="item.value" class="dimension-tab" :class="{ active: dimension === item.value }" @click="dimension = item.value">{{ item.label }}</button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" style="margin-bottom:14px"><el-button link @click="load">重新加载</el-button></el-alert>
    <RemoteState v-if="planId" :loading="loading" :error="error" :empty="!loading && !dashboard" empty-text="暂无看板数据" empty-description="当前计划下暂无可统计的方向、指标或学院任务" @retry="load">
      <template v-if="summary && dashboard">
        <div class="kpi-strip">
          <div class="kpi-cell"><div class="kpi-value">{{ summary.completion_percent }}%</div><div class="kpi-label">五年累计完成率</div><div class="kpi-note">已认定覆盖率 {{ summary.reviewed_percent }}%</div></div>
          <div class="kpi-cell"><div class="kpi-value">{{ summary.indicator_count }}</div><div class="kpi-label">指标总数</div><div class="kpi-note">{{ summary.direction_count }} 个方向 · {{ summary.task_count }} 个任务</div></div>
          <div class="kpi-cell"><div class="kpi-value success-text">{{ indicatorStats.achieved }}</div><div class="kpi-label">达标指标</div><div class="kpi-note">全部年度里程碑已完成</div></div>
          <div class="kpi-cell"><div class="kpi-value">{{ indicatorStats.inProgress }}</div><div class="kpi-label">进行中</div><div class="kpi-note">已下发且尚未完成</div></div>
          <div class="kpi-cell"><div class="kpi-value warning-text">{{ indicatorStats.notAssigned }}</div><div class="kpi-label">未下发</div><div class="kpi-note">尚未创建学院任务</div></div>
          <div class="kpi-cell"><div class="kpi-value danger-text">{{ indicatorStats.unmet }}</div><div class="kpi-label">未达标</div><div class="kpi-note">存在已认定未达标年度</div></div>
        </div>
        <div class="chart-grid">
          <section class="panel chart-panel">
            <div class="panel-header"><h3 class="panel-title">执行单元五年累计完成率排名<span class="panel-subtitle">{{ collegeRank.length }} 个学院</span></h3></div>
            <div class="rank-chart">
              <div class="rank-axis-row"><span /><div class="rank-axis"><span v-for="tick in [0, 20, 40, 60, 80, 100]" :key="tick">{{ tick }}%</span></div><span /></div>
              <div v-if="collegeRank.length" class="rank-list">
                <div v-for="row in collegeRank" :key="row.college_org_id" class="rank-row">
                  <span class="rank-name" :title="row.college_org_name">{{ row.college_org_name }}</span>
                  <div class="rank-track"><div class="rank-fill" :style="{ width: `${percent(row.progress_percent)}%` }" /></div>
                  <span class="rank-value">{{ percent(row.progress_percent).toFixed(1) }}%</span>
                </div>
              </div>
              <el-empty v-else description="暂无学院任务数据" :image-size="50" />
            </div>
          </section>
          <section class="panel chart-panel">
            <div class="panel-header"><h3 class="panel-title">目标 vs 完成（按执行单元）</h3><div class="bar-legend"><span><i class="target" />目标</span><span><i class="completed" />完成</span><span><i class="rate" />完成率</span></div></div>
            <div class="chart-body">
              <svg v-if="barPoints.length" :viewBox="`0 0 ${BAR.width} ${BAR.height}`" class="bar-svg" role="img" aria-label="各学院目标与完成情况">
                <g v-for="tick in barTicks" :key="tick">
                  <line :x1="BAR.left" :x2="BAR.width - BAR.right" :y1="barY(tick)" :y2="barY(tick)" class="bar-grid" />
                  <text :x="BAR.left - 8" :y="barY(tick) + 3" text-anchor="end" class="bar-tick">{{ tick }}</text>
                  <text :x="BAR.width - BAR.right + 8" :y="barY(tick) + 3" text-anchor="start" class="bar-tick">{{ Math.round(tick / barMax * 100) }}%</text>
                </g>
                <text :x="BAR.left - 8" :y="BAR.top - 10" text-anchor="end" class="bar-axis-title">任务数</text>
                <text :x="BAR.width - BAR.right + 8" :y="BAR.top - 10" text-anchor="start" class="bar-axis-title">完成率</text>
                <g v-for="point in barPoints" :key="point.college_org_id">
                  <rect :x="point.targetX" :y="point.targetY" :width="point.barWidth" :height="Math.max(point.targetHeight, 1)" rx="1" class="bar-target" />
                  <rect :x="point.completedX" :y="point.completedY" :width="point.barWidth" :height="Math.max(point.completedHeight, 1)" rx="1" class="bar-completed" />
                  <text :x="point.center" :y="BAR.height - 28" text-anchor="middle" class="bar-axis-label"><tspan v-for="(line, lineIndex) in nameLines(point.college_org_name)" :key="lineIndex" :x="point.center" :dy="lineIndex === 0 ? 0 : 12">{{ line }}</tspan><title>{{ point.college_org_name }}：目标 {{ point.milestone_count }}，完成 {{ point.completed }}，完成率 {{ point.progress_percent }}%</title></text>
                </g>
                <polyline :points="barRateLine" class="bar-rate-line" />
                <g v-for="point in barPoints" :key="`rate-${point.college_org_id}`">
                  <circle :cx="point.center" :cy="point.rateY" r="3.5" class="bar-rate-dot"><title>{{ point.college_org_name }}：完成率 {{ point.progress_percent }}%</title></circle>
                </g>
              </svg>
              <el-empty v-else description="暂无执行单元数据" :image-size="50" />
            </div>
          </section>
        </div>
        <section class="panel">
          <div class="panel-header"><h3 class="panel-title">年度累计完成趋势<span class="panel-subtitle">{{ dashboard.years.length }} 个年度</span></h3><span class="form-tip">累计完成率 = 截至该年度已完成里程碑数 ÷ 全部年度里程碑数；跨指标按里程碑数量统计</span></div>
          <div class="chart-body">
            <div class="chart-legend">
              <span><i class="legend-dot completed" />累计完成率</span>
              <span><i class="legend-dot reviewed" />累计认定率（含未达标）</span>
              <span><i class="legend-line" />五年目标基准线</span>
              <span v-if="chartLast" class="chart-summary">截至 {{ chartLast.year }}：累计完成 {{ chartLast.completed }} / {{ chart.total }} 个里程碑，完成率 {{ chartLast.completedRate.toFixed(1) }}%</span>
            </div>
            <div v-if="chart.total" class="chart-wrap">
              <svg :viewBox="`0 0 ${CHART.width} ${CHART.height}`" class="chart-svg" role="img" aria-label="年度累计完成趋势折线图">
                <g v-for="tick in chartTicks" :key="tick">
                  <line :x1="CHART.left" :x2="CHART.width - CHART.right" :y1="chartY(tick)" :y2="chartY(tick)" class="chart-grid" />
                  <text :x="CHART.left - 12" :y="chartY(tick) + 4" text-anchor="end" class="chart-tick">{{ tick }}%</text>
                </g>
                <line :x1="CHART.left" :x2="CHART.width - CHART.right" :y1="chartY(100)" :y2="chartY(100)" class="chart-baseline" />
                <text :x="CHART.width - CHART.right" :y="chartY(100) - 8" text-anchor="end" class="chart-baseline-label">五年目标 100%</text>
                <path :d="chartAreaPath" class="chart-area" />
                <polyline :points="reviewedPolyline" class="chart-line reviewed" />
                <polyline :points="completedPolyline" class="chart-line completed" />
                <g v-for="(point, index) in chart.points" :key="point.year">
                  <circle :cx="chartX(index)" :cy="chartY(point.reviewedRate)" r="3.5" class="chart-dot reviewed"><title>{{ point.year }} 年：累计认定 {{ point.reviewed }} / {{ chart.total }}（{{ point.reviewedRate.toFixed(1) }}%）</title></circle>
                  <circle :cx="chartX(index)" :cy="chartY(point.completedRate)" r="4.5" class="chart-dot completed"><title>{{ point.year }} 年：累计完成 {{ point.completed }} / {{ chart.total }}（{{ point.completedRate.toFixed(1) }}%）</title></circle>
                  <text :x="chartX(index)" :y="chartY(point.completedRate) - 11" text-anchor="middle" class="chart-point-label">{{ point.completedRate.toFixed(0) }}%</text>
                  <text :x="chartX(index)" :y="CHART.height - 14" text-anchor="middle" class="chart-axis-label">{{ point.year }}</text>
                </g>
              </svg>
            </div>
            <el-empty v-else description="尚无年度里程碑数据" :image-size="60" />
          </div>
        </section>
        <section class="panel detail-panel">
          <div class="panel-header"><h3 class="panel-title">明细表（按{{ dimensionLabel }}）<span class="panel-subtitle">{{ dimension === 'COLLEGE' ? collegeRank.length : dimension === 'INDICATOR' ? filteredIndicators.length : dimension === 'DIRECTION' ? dashboard.directions.length : typeRows.length }} 项</span></h3><span class="form-tip">跨指标只统计任务和里程碑数量，不混合单位加总</span></div>
          <el-table v-if="dimension === 'COLLEGE'" :data="collegeRank" row-key="college_org_id" max-height="540">
            <el-table-column prop="college_org_name" label="执行学院" min-width="180" show-overflow-tooltip />
            <el-table-column prop="task_count" label="任务数" width="90" align="right" />
            <el-table-column label="年度里程碑" width="115" align="right"><template #default="{ row }">{{ row.completed }} / {{ row.milestone_count }}</template></el-table-column>
            <el-table-column prop="under_review" label="审核中" width="90" align="right" />
            <el-table-column label="未达标" width="90" align="right"><template #default="{ row }"><span :class="{ 'danger-text': row.unmet }">{{ row.unmet }}</span></template></el-table-column>
            <el-table-column label="五年累计完成率" min-width="230"><template #default="{ row }"><div class="progress-cell"><div class="progress-head"><span>{{ progressText(row) }}</span><strong>{{ progressValue(row) }}%</strong></div><div class="progress-track"><div class="progress-fill" :class="progressTone(row)" :style="{ width: `${progressValue(row)}%` }" /></div></div></template></el-table-column>
          </el-table>
          <el-table v-else-if="dimension === 'INDICATOR'" :data="filteredIndicators" row-key="id" max-height="540">
            <el-table-column label="关键产出指标" min-width="210"><template #default="{ row }"><div class="indicator-name" :title="row.indicator_name">{{ row.indicator_name }}</div><div class="mono subtle small">{{ row.indicator_code }}</div></template></el-table-column>
            <el-table-column label="完成进度" min-width="210"><template #default="{ row }"><div class="progress-cell"><div class="progress-head"><span>{{ progressText(row) }}</span><strong>{{ progressValue(row) }}%</strong></div><div class="progress-track"><div class="progress-fill" :class="progressTone(row)" :style="{ width: `${progressValue(row)}%` }" /></div><div v-if="amountText(row)" class="progress-note">{{ amountText(row) }}</div><div v-else-if="statusCountsText(row.task_status_counts)" class="progress-note">{{ statusCountsText(row.task_status_counts) }}</div></div></template></el-table-column>
            <el-table-column prop="direction_name" label="建设方向" width="120" show-overflow-tooltip />
            <el-table-column label="类型／单位" width="100"><template #default="{ row }"><StatusTag :status="row.indicator_type_code" /><div class="subtle small" style="margin-top:4px">{{ row.unit_name || row.unit_code }}</div></template></el-table-column>
            <el-table-column label="任务" width="75" align="center"><template #default="{ row }"><span v-if="row.task_count">{{ row.task_count }}</span><span v-else class="subtle">未下发</span></template></el-table-column>
            <el-table-column label="里程碑" width="95" align="center"><template #default="{ row }">{{ row.completed }} / {{ row.milestone_count }}</template></el-table-column>
            <el-table-column prop="under_review" label="审核中" width="70" align="center" />
            <el-table-column label="未达标" width="70" align="center"><template #default="{ row }"><span :class="{ 'danger-text': row.unmet }">{{ row.unmet }}</span></template></el-table-column>
          </el-table>
          <el-table v-else-if="dimension === 'DIRECTION'" :data="dashboard.directions" row-key="id" max-height="540">
            <el-table-column prop="direction_name" label="建设方向" min-width="220" show-overflow-tooltip />
            <el-table-column prop="indicator_count" label="指标数" width="90" align="right" />
            <el-table-column prop="task_count" label="任务数" width="90" align="right" />
            <el-table-column label="年度里程碑" width="115" align="right"><template #default="{ row }">{{ row.completed }} / {{ row.milestone_count }}</template></el-table-column>
            <el-table-column label="未达标" width="90" align="right"><template #default="{ row }"><span :class="{ 'danger-text': row.unmet }">{{ row.unmet }}</span></template></el-table-column>
            <el-table-column label="五年累计完成率" min-width="230"><template #default="{ row }"><div class="progress-cell"><div class="progress-head"><span>{{ progressText(row) }}</span><strong>{{ progressValue(row) }}%</strong></div><div class="progress-track"><div class="progress-fill" :class="progressTone(row)" :style="{ width: `${progressValue(row)}%` }" /></div></div></template></el-table-column>
          </el-table>
          <el-table v-else :data="typeRows" row-key="indicator_type_code" max-height="540">
            <el-table-column label="指标性质" min-width="180"><template #default="{ row }"><StatusTag :status="row.indicator_type_code" /></template></el-table-column>
            <el-table-column prop="indicator_count" label="指标数" width="90" align="right" />
            <el-table-column prop="task_count" label="任务数" width="90" align="right" />
            <el-table-column label="年度里程碑" width="115" align="right"><template #default="{ row }">{{ row.completed }} / {{ row.milestone_count }}</template></el-table-column>
            <el-table-column label="未达标" width="90" align="right"><template #default="{ row }"><span :class="{ 'danger-text': row.unmet }">{{ row.unmet }}</span></template></el-table-column>
            <el-table-column label="五年累计完成率" min-width="230"><template #default="{ row }"><div class="progress-cell"><div class="progress-head"><span>{{ progressText(row) }}</span><strong>{{ progressValue(row) }}%</strong></div><div class="progress-track"><div class="progress-fill" :class="progressTone(row)" :style="{ width: `${progressValue(row)}%` }" /></div></div></template></el-table-column>
          </el-table>
        </section>
      </template>
    </RemoteState>
    <div v-else class="state-block"><h3>请选择建设计划</h3><p>选择计划后查看执行单元、指标、方向和指标性质的完成情况。</p></div>
  </div>
</template>
<style scoped>
.analytics-filter{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:12px 14px;margin-bottom:14px;background:#fff;border:1px solid var(--line);border-radius:6px}
.filter-plan{width:300px}
.filter-meta{display:flex;align-items:center;gap:8px;color:#7c8799;font-size:12px}
.dimension-tabs{display:flex;align-items:center;gap:2px;margin-left:auto;padding:2px;background:#f3f6fb;border-radius:5px}
.dimension-tab{padding:6px 13px;border:0;border-radius:4px;background:transparent;color:#5f6d82;font-size:12px;white-space:nowrap;cursor:pointer}
.dimension-tab:hover{color:#2458d3}
.dimension-tab.active{color:#fff;background:#2458d3;font-weight:600;box-shadow:0 2px 6px rgba(36,88,211,.22)}
.kpi-strip{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));margin-bottom:14px;background:#fff;border:1px solid var(--line);border-radius:6px}
.kpi-cell{padding:15px 12px;text-align:center;border-right:1px solid var(--line)}
.kpi-cell:last-child{border-right:0}
.kpi-value{font-size:24px;font-weight:650;line-height:1.1;font-variant-numeric:tabular-nums}
.kpi-label{margin-top:6px;color:#7c8799;font-size:12px}
.kpi-note{margin-top:5px;color:#a0aabb;font-size:10px;line-height:1.4}
.chart-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.35fr);gap:14px;margin-bottom:14px}
.chart-panel{min-width:0}
.rank-chart{padding:16px}
.rank-axis-row{display:grid;grid-template-columns:130px minmax(0,1fr) 56px;gap:10px;align-items:center;margin-bottom:10px}
.rank-axis{display:flex;justify-content:space-between;color:#9aa5b8;font-size:10px}
.rank-row{display:grid;grid-template-columns:130px minmax(0,1fr) 56px;gap:10px;align-items:center;margin-bottom:13px}
.rank-name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#55647a;font-size:11px;text-align:right}
.rank-track{height:12px;overflow:hidden;background:#f0f3f9;border-radius:2px}
.rank-fill{height:100%;background:linear-gradient(90deg,#5b7fd1,#2f5fbf);border-radius:2px;transition:width .25s ease}
.rank-value{color:#3d4b63;font-size:11px;font-variant-numeric:tabular-nums;text-align:right}
.bar-legend{display:flex;align-items:center;gap:14px;color:#65738b;font-size:11px}
.bar-legend span{display:inline-flex;align-items:center;gap:5px}
.bar-legend i{display:inline-block;width:9px;height:9px;border-radius:2px}
.bar-legend .target{background:#c7d7f6}
.bar-legend .completed{background:#2f5fbf}
.bar-legend .rate{width:16px;height:0;border-top:2px solid #e08a2e;border-radius:0}
.bar-svg{display:block;width:100%;height:auto}
.bar-grid{stroke:#edf1f7;stroke-width:1}
.bar-target{fill:#c7d7f6}
.bar-completed{fill:#2f5fbf}
.bar-axis-label{fill:#6f7c90;font-size:11px}
.bar-tick{fill:#8b97a9;font-size:10px}
.bar-axis-title{fill:#8b97a9;font-size:10px}
.bar-rate-line{fill:none;stroke:#e08a2e;stroke-width:1.8;stroke-linejoin:round}
.bar-rate-dot{fill:#fff;stroke:#e08a2e;stroke-width:1.6}
.detail-panel{margin-top:14px}
.indicator-name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:550}
.progress-cell{min-width:0}
.progress-head{display:flex;align-items:center;justify-content:space-between;gap:8px;color:#68788f;font-size:11px}
.progress-head strong{color:#22314d;font-size:12px;font-variant-numeric:tabular-nums}
.progress-track{height:6px;margin-top:5px;overflow:hidden;background:#e6ebf3;border-radius:3px}
.progress-fill{height:100%;background:#4c7be0;border-radius:3px;transition:width .25s ease}
.progress-fill.done{background:#2b9b73}
.progress-fill.risk{background:#d97b32}
.progress-note{margin-top:4px;color:#8a97a8;font-size:10px;line-height:1.5}
.chart-body{padding:14px 18px 10px}
.chart-legend{display:flex;align-items:center;flex-wrap:wrap;gap:16px;color:#65738b;font-size:12px}
.chart-legend span{display:inline-flex;align-items:center;gap:6px}
.legend-dot{display:inline-block;width:9px;height:9px;border-radius:50%}
.legend-dot.completed{background:#4c7be0}
.legend-dot.reviewed{background:#2b9b73}
.legend-line{display:inline-block;width:20px;height:0;border-top:2px dashed #d97b32}
.chart-summary{margin-left:auto;color:#8a97a8}
.chart-wrap{margin-top:4px}
.chart-svg{display:block;width:100%;height:auto}
.chart-grid{stroke:#e9eef6;stroke-width:1}
.chart-tick{fill:#94a0b3;font-size:12px}
.chart-baseline{stroke:#d97b32;stroke-width:1.5;stroke-dasharray:8 5}
.chart-baseline-label{fill:#b76a12;font-size:12px;font-weight:600}
.chart-area{fill:rgba(76,123,224,.08)}
.chart-line{fill:none;stroke-linejoin:round;stroke-linecap:round}
.chart-line.completed{stroke:#4c7be0;stroke-width:2.5}
.chart-line.reviewed{stroke:#2b9b73;stroke-width:2;stroke-dasharray:6 4}
.chart-dot{fill:#fff}
.chart-dot.completed{stroke:#4c7be0;stroke-width:2}
.chart-dot.reviewed{stroke:#2b9b73;stroke-width:2}
.chart-point-label{fill:#34415c;font-size:11px;font-weight:600}
.chart-axis-label{fill:#65738b;font-size:12px}
@media(max-width:1100px){.chart-grid{grid-template-columns:1fr}.kpi-strip{grid-template-columns:repeat(3,minmax(0,1fr))}.kpi-cell:nth-child(3){border-right:0}.kpi-cell:nth-child(-n+3){border-bottom:1px solid var(--line)}}
@media(max-width:900px){.kpi-strip{grid-template-columns:repeat(2,minmax(0,1fr))}.kpi-cell:nth-child(3){border-right:1px solid var(--line)}.kpi-cell:nth-child(even){border-right:0}.dimension-tabs{margin-left:0}.filter-plan{width:100%}.chart-summary{margin-left:0;width:100%}}
</style>
