<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FullScreen } from '@element-plus/icons-vue'
import { listPlans } from '@/api/plan'
import { getScreenData } from '@/api/screen'
import { getStatsTree } from '@/api/stats'
import { useCountUp, useEChart } from '@/utils/chart'
import { computeScreenScale, throttleTrailing } from '@/utils/screenScale'
import { formatCount, formatLargeNumber, formatPercent, timeProgressOfPlan } from '@/utils/format'
import type { PlanCard } from '@/types/plan'
import type {
  IndicatorStat,
  ScreenActivity,
  ScreenData,
  StatsTreeNode,
  WarningItem,
} from '@/types/stats'

const REFRESH_INTERVAL_S = 60
const RETRY_INTERVAL_S = 30
const SCROLL_INTERVAL_MS = 3000
/** 列表超过该条数才自动滚动 */
const SCROLL_THRESHOLD = 5

const route = useRoute()
const router = useRouter()

const kiosk = computed(() => route.query.kiosk === '1')

const plans = ref<PlanCard[]>([])
const planId = ref<string>((route.params.planId as string) || (route.query.planId as string) || '')
/** null 表示五年累计 */
const year = ref<number | null>(route.query.year ? Number(route.query.year) : null)
const yearSelection = computed<number | 'ALL'>({
  get: () => year.value ?? 'ALL',
  set: (value) => {
    year.value = value === 'ALL' ? null : value
  },
})

const data = ref<ScreenData | null>(null)
const firstLoading = ref(true)
const refreshFailed = ref(false)
const updatedAt = ref<Date | null>(null)
const countdown = ref(REFRESH_INTERVAL_S)
const now = ref(new Date())
const scale = ref(computeScreenScale(window.innerWidth, window.innerHeight))
const isFullscreen = ref(false)

const yearOptions = computed<number[]>(() => {
  const p = data.value?.plan
  if (!p) return []
  const list: number[] = []
  for (let y = p.startYear; y <= p.endYear; y += 1) list.push(y)
  return list
})

const overview = computed(() => data.value?.overview ?? null)

const clockText = computed(() => {
  const n = now.value
  const p = (v: number) => (v < 10 ? `0${v}` : `${v}`)
  const weeks = ['日', '一', '二', '三', '四', '五', '六']
  return `${n.getFullYear()}-${p(n.getMonth() + 1)}-${p(n.getDate())} ${p(n.getHours())}:${p(
    n.getMinutes(),
  )}:${p(n.getSeconds())} 星期${weeks[n.getDay()]}`
})

const updatedText = computed(() => {
  if (!updatedAt.value) return ''
  const p = (v: number) => (v < 10 ? `0${v}` : `${v}`)
  const t = updatedAt.value
  return `${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}`
})

// ---------- 图表 ----------
const gaugeChart = useEChart('fzsu-dark')
const indicatorChart = useEChart('fzsu-dark')
const trendChart = useEChart('fzsu-dark')
const unitChart = useEChart('fzsu-dark')

function renderGauge(): void {
  // 五年累计完成率（五年总量 = 100%）
  const value = overview.value?.cumulativeCompletionRate ?? null
  gaugeChart.setOption({
    series: [
      {
        type: 'gauge',
        startAngle: 210,
        endAngle: -30,
        min: 0,
        max: 100,
        radius: '88%',
        center: ['50%', '58%'],
        progress: { show: true, width: 14, itemStyle: { color: '#3DD6F5' } },
        axisLine: { lineStyle: { width: 14, color: [[1, 'rgba(79, 139, 255, 0.2)']] } },
        splitNumber: 5,
        axisTick: {
          show: true,
          distance: 6,
          length: 5,
          lineStyle: { color: 'rgba(143, 167, 214, 0.45)', width: 1 },
        },
        splitLine: {
          show: true,
          distance: 6,
          length: 11,
          lineStyle: { color: 'rgba(143, 167, 214, 0.45)', width: 2 },
        },
        axisLabel: { show: false },
        pointer: { show: false },
        detail: {
          valueAnimation: true,
          fontSize: 52,
          fontWeight: 600,
          color: '#3DD6F5',
          fontFamily: 'DIN Alternate, Bahnschrift, Helvetica Neue, Arial, sans-serif',
          formatter: (v: number) => `${v.toFixed(1)}%`,
          offsetCenter: [0, '-4%'],
        },
        data: [{ value: value == null ? 0 : Number((value * 1000).toFixed(0)) / 10 }],
      },
    ],
  })
}

function renderIndicator(): void {
  const items = data.value?.byIndicator ?? []
  indicatorChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps: unknown) => {
        const item = items[(ps as { dataIndex?: number }[])[0]?.dataIndex ?? 0]
        if (!item) return ''
        const rate = item.completionRate == null ? '—' : `${(item.completionRate * 100).toFixed(1)}%`
        const cumRate = item.cumulativeRate == null ? '—' : `${(item.cumulativeRate * 100).toFixed(1)}%`
        return `${item.name}<br/>目标 ${item.taskCount} · 完成 ${item.achievedCount} · 当年完成率 ${rate} · 五年累计 ${cumRate}<br/><span style="color:#8FA7D6">点击查看 A → A+ → B 下钻明细</span>`
      },
    },
    legend: { top: 0, itemWidth: 14, itemHeight: 8 },
    grid: { left: 8, right: 16, top: 28, bottom: 4, containLabel: true },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      inverse: true,
      data: items.map((item) => item.name),
      axisLabel: { width: 120, overflow: 'truncate' },
    },
    series: [
      {
        name: '目标',
        type: 'bar',
        barWidth: 8,
        barGap: '40%',
        itemStyle: { color: 'rgba(79, 139, 255, 0.35)', borderRadius: [0, 3, 3, 0] },
        data: items.map((item) => item.taskCount),
      },
      {
        name: '完成',
        type: 'bar',
        barWidth: 8,
        itemStyle: { color: '#3DD6F5', borderRadius: [0, 3, 3, 0] },
        data: items.map((item) => item.achievedCount),
      },
    ],
  })
  const chart = indicatorChart.chart.value
  if (chart) {
    chart.off('click')
    chart.on('click', (params) => {
      const item = items[params.dataIndex ?? -1]
      if (item) void openIndicatorDrill(item)
    })
  }
}

function renderTrend(): void {
  const points = data.value?.trend ?? []
  const currentYear = new Date().getFullYear()
  const timeProgress = overview.value?.timeProgress
  const markValue = timeProgress == null ? null : Number((timeProgress * 100).toFixed(1))
  const currentIndex = points.findIndex((p) => p.year === currentYear)
  trendChart.setOption({
    legend: { top: 0, itemWidth: 14, itemHeight: 8, data: ['目标', '实际'] },
    grid: { left: 8, right: 44, top: 28, bottom: 4, containLabel: true },
    xAxis: { type: 'category', data: points.map((p) => `${p.year}`) },
    yAxis: [
      { type: 'value', name: '任务数' },
      { type: 'value', name: '时间进度', max: 100, axisLabel: { formatter: '{value}%' } },
    ],
    series: [
      {
        name: '目标',
        type: 'bar',
        barWidth: 26,
        data: points.map((p) => ({
          value: p.taskCount,
          itemStyle: {
            color: '#BFCEEC',
            opacity: p.year > currentYear ? 0.4 : 0.95,
            borderRadius: [3, 3, 0, 0],
          },
        })),
      },
      {
        name: '实际',
        type: 'line',
        smooth: true,
        symbolSize: 7,
        connectNulls: false,
        lineStyle: { color: '#3DD6F5', width: 3 },
        itemStyle: { color: '#3DD6F5' },
        data: points.map((p) => (p.year > currentYear ? null : p.achievedCount)),
      },
      {
        name: '时间进度',
        type: 'line',
        yAxisIndex: 1,
        data: [],
        markLine:
          markValue == null
            ? undefined
            : {
                symbol: 'none',
                animation: false,
                silent: true,
                lineStyle: { color: '#FFB547', type: 'dashed', width: 2 },
                label: {
                  color: '#FFB547',
                  fontSize: 12,
                  formatter: `时间进度 ${markValue.toFixed(1)}%`,
                },
                data: [
                  currentIndex >= 0
                    ? [{ coord: [0, markValue] }, { coord: [currentIndex, markValue] }]
                    : { yAxis: markValue },
                ],
              },
      },
    ],
  })
}

const RANK_COLORS = ['#FFB547', '#A8B2C5', '#D69A66']

function renderUnitRank(): void {
  // 五年累计完成率排名（五年总量口径）
  const items = [...(data.value?.byUnit ?? [])].sort(
    (a, b) => (b.cumulativeRate ?? -1) - (a.cumulativeRate ?? -1),
  )
  unitChart.setOption({
    grid: { left: 8, right: 44, top: 4, bottom: 4, containLabel: true },
    xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    yAxis: {
      type: 'category',
      inverse: true,
      data: items.map((item) => item.unit?.name ?? '—'),
      axisLabel: { width: 108, overflow: 'truncate' },
    },
    series: [
      {
        type: 'bar',
        barWidth: 12,
        data: items.map((item, index) => ({
          value: item.cumulativeRate == null ? 0 : Number((item.cumulativeRate * 100).toFixed(1)),
          itemStyle: {
            color: RANK_COLORS[index] ?? '#4F8BFF',
            borderRadius: [0, 3, 3, 0],
          },
        })),
        label: {
          show: true,
          position: 'right',
          color: '#8FA7D6',
          formatter: (p: { dataIndex: number }) =>
            items[p.dataIndex]?.cumulativeRate == null
              ? '—'
              : `${((items[p.dataIndex].cumulativeRate ?? 0) * 100).toFixed(1)}%`,
        },
      },
    ],
  })
}

async function renderCharts(): Promise<void> {
  await nextTick()
  renderGauge()
  renderIndicator()
  renderTrend()
  renderUnitRank()
}

// ---------- 滚动列表 ----------
const warnings = ref<WarningItem[]>([])
const activities = ref<ScreenActivity[]>([])
const warningPaused = ref(false)
const activityPaused = ref(false)

let scrollTimer: ReturnType<typeof setInterval> | null = null

function startScrollTimer(): void {
  scrollTimer = setInterval(() => {
    if (!warningPaused.value && warnings.value.length > SCROLL_THRESHOLD) {
      const first = warnings.value.shift()
      if (first) warnings.value.push(first)
    }
    if (!activityPaused.value && activities.value.length > SCROLL_THRESHOLD) {
      const first = activities.value.shift()
      if (first) activities.value.push(first)
    }
  }, SCROLL_INTERVAL_MS)
}

// ---------- 刷新 ----------
let tickTimer: ReturnType<typeof setInterval> | null = null
let refreshing = false

async function refresh(): Promise<void> {
  if (!planId.value || refreshing) return
  refreshing = true
  try {
    const result = await getScreenData(planId.value, year.value ?? undefined)
    data.value = result
    warnings.value = [...(result.overview?.warnings ?? [])]
    activities.value = [...result.recentActivities]
    updatedAt.value = new Date()
    refreshFailed.value = false
    countdown.value = REFRESH_INTERVAL_S
    await renderCharts()
  } catch {
    // 接口失败：保留上次数据，30 秒后重试（UI 规范 9.4）
    refreshFailed.value = true
    countdown.value = RETRY_INTERVAL_S
  } finally {
    refreshing = false
    firstLoading.value = false
  }
}

function handlePlanChange(): void {
  year.value = null
  syncUrl()
  void refresh()
}

function handleYearChange(): void {
  syncUrl()
  void refresh()
}

function syncUrl(): void {
  if (kiosk.value) return
  router.replace({
    params: planId.value ? { planId: planId.value } : {},
    query: year.value != null ? { year: year.value } : {},
  })
}

// ---------- 全屏 ----------
function toggleFullscreen(): void {
  if (document.fullscreenElement) {
    void document.exitFullscreen()
  } else {
    void document.documentElement.requestFullscreen()
  }
}

function onFullscreenChange(): void {
  isFullscreen.value = document.fullscreenElement !== null
}

// 缩放重算 100ms trailing 节流
const onResize = throttleTrailing(() => {
  scale.value = computeScreenScale(window.innerWidth, window.innerHeight)
}, 100)

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape' && drillVisible.value) closeIndicatorDrill()
}

function goBack(): void {
  router.push('/plans')
}

onMounted(async () => {
  window.addEventListener('resize', onResize)
  window.addEventListener('keydown', onKeydown)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  startScrollTimer()
  tickTimer = setInterval(() => {
    now.value = new Date()
    countdown.value -= 1
    if (countdown.value <= 0) void refresh()
  }, 1000)

  if (!kiosk.value || !planId.value) {
    try {
      plans.value = await listPlans()
    } catch {
      plans.value = []
    }
  }
  if (!planId.value && plans.value.length > 0) {
    planId.value = (plans.value.find((p) => p.status === 'ACTIVE') ?? plans.value[0]).id
    syncUrl()
  }
  await refresh()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('keydown', onKeydown)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  onResize.cancel()
  if (tickTimer) clearInterval(tickTimer)
  if (scrollTimer) clearInterval(scrollTimer)
})

// ---------- ④ 核心数字条 / ③ 调整统计（数字滚动，规范 9.2） ----------
const coreNumbers = computed(() => data.value?.coreNumbers ?? null)
const adjustmentStats = computed(() => data.value?.adjustmentStats ?? null)

const coreADisplay = useCountUp(computed(() => coreNumbers.value?.indicatorACount), formatLargeNumber)
const coreAPlusDisplay = useCountUp(computed(() => coreNumbers.value?.indicatorAPlusCount), formatLargeNumber)
const coreTaskDisplay = useCountUp(computed(() => coreNumbers.value?.taskCount), formatLargeNumber)
const coreMaterialDisplay = useCountUp(computed(() => coreNumbers.value?.materialCount), formatLargeNumber)
const coreApprovedDisplay = useCountUp(
  computed(() => coreNumbers.value?.approvedThisYearCount),
  formatLargeNumber,
)

const adjustPostponeDisplay = useCountUp(computed(() => adjustmentStats.value?.postponeCount), formatCount)
const adjustTransferDisplay = useCountUp(computed(() => adjustmentStats.value?.transferCount), formatCount)
const adjustPendingDisplay = useCountUp(computed(() => adjustmentStats.value?.pendingCount), formatCount)
const adjustApprovedDisplay = useCountUp(computed(() => adjustmentStats.value?.approvedCount), formatCount)

// ---------- ① 完成进度 vs 时间进度（▲ 领先 / ▼ 落后，规范 9.2） ----------
/** 规划期时间进度（五年口径：已过天数 ÷ 规划期总天数） */
const planTimeProgress = computed<number | null>(() => {
  const p = data.value?.plan
  if (!p) return null
  return timeProgressOfPlan(p.startYear, p.endYear)
})

const progressDelta = computed<number | null>(() => {
  const o = overview.value
  if (!o || o.cumulativeCompletionRate == null || planTimeProgress.value == null) return null
  return o.cumulativeCompletionRate - planTimeProgress.value
})

const progressDeltaText = computed(() => {
  const d = progressDelta.value
  if (d == null) return ''
  return `${d >= 0 ? '+' : '-'}${(Math.abs(d) * 100).toFixed(1)}`
})

// ---------- ⑤ A 级指标下钻 ----------
interface DrillRow {
  node: StatsTreeNode
  depth: number
}

const drillVisible = ref(false)
const drillLoading = ref(false)
const drillIndicator = ref<IndicatorStat | null>(null)
const drillTree = ref<StatsTreeNode[]>([])

const drillRows = computed<DrillRow[]>(() => {
  const rows: DrillRow[] = []
  const walk = (nodes: StatsTreeNode[], depth: number): void => {
    for (const node of nodes) {
      rows.push({ node, depth })
      if (node.children?.length) walk(node.children, depth + 1)
    }
  }
  walk(drillTree.value, 0)
  return rows
})

async function openIndicatorDrill(item: IndicatorStat): Promise<void> {
  drillIndicator.value = item
  drillVisible.value = true
  drillLoading.value = true
  drillTree.value = []
  try {
    const tree = await getStatsTree(planId.value, year.value ?? undefined)
    const root = tree.find((node) => node.level === 'A' && node.id === item.indicatorId)
    drillTree.value = root ? [root] : []
  } catch {
    drillTree.value = []
  } finally {
    drillLoading.value = false
  }
}

function closeIndicatorDrill(): void {
  drillVisible.value = false
}

/** 达成状态色点（深色主题功能色，规范 2.6） */
const ACHIEVE_DOT_COLORS: Record<string, string> = {
  ACHIEVED: '#3DDC97',
  EXCEEDED: '#3DDC97',
  NOT_ACHIEVED: '#FF5B5B',
  LAGGING: '#FFB547',
  IN_PROGRESS: '#4F8BFF',
  NOT_STARTED: '#8FA7D6',
}

function achieveDotColor(node: StatsTreeNode): string {
  if (node.achieveStatus && ACHIEVE_DOT_COLORS[node.achieveStatus]) {
    return ACHIEVE_DOT_COLORS[node.achieveStatus]
  }
  if (node.isAchieved === true) return '#3DDC97'
  if (node.isAchieved === false) return '#FF5B5B'
  return 'rgba(143, 167, 214, 0.45)'
}

function levelLabel(level: StatsTreeNode['level']): string {
  return level === 'A' ? 'A' : level === 'A_PLUS' ? 'A+' : 'B'
}

/** 节点数值摘要：数值型显示「实际 / 目标」，是否型显示计划完成 */
function nodeValueText(node: StatsTreeNode): string {
  if (typeof node.target === 'number') {
    const actual = node.actualValue == null ? '—' : formatCount(node.actualValue)
    return `${actual} / ${formatCount(node.target)}`
  }
  if (typeof node.target === 'boolean') {
    return node.target ? '计划完成' : ''
  }
  return ''
}

/** 预警展示文案与级别（lagGap 落后差距，小数） */
function warningText(w: WarningItem): string {
  const rate = w.completionRate == null ? '—' : formatPercent(w.completionRate)
  return `${w.unit?.name ?? ''} · ${w.indicatorName}（${w.year}）：完成率 ${rate}`
}

function warningLevel(w: WarningItem): 'RED' | 'ORANGE' {
  return (w.lagGap ?? 0) >= 0.3 ? 'RED' : 'ORANGE'
}

/** 最近动态文案（审核流水） */
function activityText(a: ScreenActivity): string {
  const result = a.result === 'APPROVED' ? '审核通过' : a.result === 'REJECTED' ? '审核驳回' : a.result
  return `${a.operatorName} ${result} ${a.taskCode}（${a.year}）`
}
</script>

<template>
  <div class="screen-viewport">
    <div
      v-if="scale > 0"
      class="screen-canvas"
      :style="{ transform: `translate(-50%, -50%) scale(${scale})` }"
    >
      <!-- 顶部标题栏 -->
      <header class="screen-header">
        <div class="header-left">
          <div class="header-clock">{{ clockText }}</div>
          <div v-if="overview?.timeProgress != null" class="header-progress">
            时间进度 <span class="num text-orange">{{ formatPercent(overview.timeProgress) }}</span>
          </div>
        </div>
        <h1 class="screen-title">福州软件职业技术学院 · 五年发展规划指标监测</h1>
        <div class="header-right">
          <template v-if="!kiosk">
            <el-select
              v-model="planId"
              class="header-select"
              size="small"
              teleported
              @change="handlePlanChange"
            >
              <el-option v-for="p in plans" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
            <el-select
              v-model="yearSelection"
              class="header-select year-select"
              size="small"
              teleported
              @change="handleYearChange"
            >
              <el-option label="五年累计" value="ALL" />
              <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年`" :value="y" />
            </el-select>
            <button class="header-btn" type="button" @click="goBack">返回</button>
          </template>
          <button class="header-btn" type="button" @click="toggleFullscreen">
            <el-icon :size="14"><FullScreen /></el-icon>
            {{ isFullscreen ? '退出全屏' : '全屏' }}
          </button>
          <div class="header-refresh" :class="{ 'is-failed': refreshFailed }">
            <template v-if="refreshFailed">
              <span class="fail-dot" />数据刷新失败，{{ countdown }}s 后重试
            </template>
            <template v-else>数据更新于 {{ updatedText || '—' }} · {{ countdown }}s</template>
          </div>
        </div>
      </header>

      <main v-if="data && overview" class="screen-main">
        <!-- 左列 -->
        <section class="col col-left">
          <div class="panel panel-overview">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="panel-title">五年总完成率</div>
            <div :ref="gaugeChart.container" class="gauge-box" />
            <div class="dual-progress">
              <div class="dp-row">
                <span class="dp-label">完成进度</span>
                <div class="dp-track">
                  <div
                    class="dp-fill dp-fill-cyan"
                    :style="{ width: `${Math.min(100, (overview.cumulativeCompletionRate ?? 0) * 100)}%` }"
                  />
                </div>
                <span class="dp-value num">{{ formatPercent(overview.cumulativeCompletionRate) }}</span>
                <span
                  v-if="progressDelta != null"
                  class="dp-delta num"
                  :class="progressDelta >= 0 ? 'is-up' : 'is-down'"
                  :title="progressDelta >= 0 ? '完成进度领先时间进度' : '完成进度落后时间进度'"
                >
                  {{ progressDelta >= 0 ? '▲' : '▼' }}{{ progressDeltaText }}
                </span>
              </div>
              <div class="dp-row">
                <span class="dp-label">时间进度</span>
                <div class="dp-track">
                  <div
                    class="dp-fill dp-fill-orange"
                    :style="{ width: `${Math.min(100, (planTimeProgress ?? 0) * 100)}%` }"
                  />
                </div>
                <span class="dp-value num text-orange">{{ formatPercent(planTimeProgress) }}</span>
                <span v-if="progressDelta != null" class="dp-delta dp-delta-placeholder" aria-hidden="true" />
              </div>
            </div>
          </div>

          <div class="panel panel-nature">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="panel-title">约束性 / 发展性达标</div>
            <div class="nature-row">
              <div class="nature-head">
                <span class="nature-tag nature-tag-danger">约束性</span>
                <span class="num nature-num">
                  {{ overview.constraintAchieved }}<span class="nature-total"> / {{ overview.constraintTotal }}</span>
                </span>
              </div>
              <div class="dp-track">
                <div
                  class="dp-fill dp-fill-red"
                  :style="{
                    width: `${overview.constraintTotal > 0 ? Math.min(100, (overview.constraintAchieved / overview.constraintTotal) * 100) : 0}%`,
                  }"
                />
              </div>
            </div>
            <div class="nature-row">
              <div class="nature-head">
                <span class="nature-tag nature-tag-teal">发展性</span>
                <span class="num nature-num">
                  {{ overview.developmentAchieved }}<span class="nature-total"> / {{ overview.developmentTotal }}</span>
                </span>
              </div>
              <div class="dp-track">
                <div
                  class="dp-fill dp-fill-green"
                  :style="{
                    width: `${overview.developmentTotal > 0 ? Math.min(100, (overview.developmentAchieved / overview.developmentTotal) * 100) : 0}%`,
                  }"
                />
              </div>
            </div>
          </div>

          <div class="panel panel-adjust">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="panel-title">指标调整统计</div>
            <div class="adjust-grid">
              <div class="adjust-item">
                <div class="num adjust-num">{{ adjustPostponeDisplay }}</div>
                <div class="adjust-label">延期</div>
              </div>
              <div class="adjust-item">
                <div class="num adjust-num">{{ adjustTransferDisplay }}</div>
                <div class="adjust-label">转移</div>
              </div>
              <div class="adjust-item">
                <div class="num adjust-num">{{ adjustPendingDisplay }}</div>
                <div class="adjust-label">审批中</div>
              </div>
              <div class="adjust-item">
                <div class="num adjust-num">{{ adjustApprovedDisplay }}</div>
                <div class="adjust-label">已生效</div>
              </div>
            </div>
          </div>
        </section>

        <!-- 中列 -->
        <section class="col col-middle">
          <div class="panel panel-core">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="core-item">
              <div class="num core-num">{{ coreADisplay }}</div>
              <div class="core-label">A 类指标</div>
            </div>
            <div class="core-item">
              <div class="num core-num">{{ coreAPlusDisplay }}</div>
              <div class="core-label">A+ 类指标</div>
            </div>
            <div class="core-item">
              <div class="num core-num">{{ coreTaskDisplay }}</div>
              <div class="core-label">B 任务</div>
            </div>
            <div class="core-item">
              <div class="num core-num">{{ coreMaterialDisplay }}</div>
              <div class="core-label">佐证材料</div>
            </div>
            <div class="core-item">
              <div class="num core-num">{{ coreApprovedDisplay }}</div>
              <div class="core-label">本年审核通过</div>
            </div>
          </div>

          <div class="panel panel-indicator">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="panel-title">A 级指标完成情况</div>
            <div v-if="data.byIndicator.length === 0" class="panel-empty">暂无数据</div>
            <div v-show="data.byIndicator.length > 0" :ref="indicatorChart.container" class="panel-chart" />
          </div>

          <div class="panel panel-trend">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="panel-title">{{ data.plan?.startYear }}–{{ data.plan?.endYear }} 目标与实际趋势</div>
            <div v-if="data.trend.length === 0" class="panel-empty">暂无数据</div>
            <div v-show="data.trend.length > 0" :ref="trendChart.container" class="panel-chart" />
          </div>
        </section>

        <!-- 右列 -->
        <section class="col col-right">
          <div class="panel panel-rank">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="panel-title">执行单元五年累计完成率排名</div>
            <div v-if="data.byUnit.length === 0" class="panel-empty">暂无数据</div>
            <div v-show="data.byUnit.length > 0" :ref="unitChart.container" class="panel-chart" />
          </div>

          <div class="panel panel-warn">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="panel-title">滞后预警</div>
            <div
              class="scroll-list"
              @mouseenter="warningPaused = true"
              @mouseleave="warningPaused = false"
            >
              <div v-if="warnings.length === 0" class="panel-empty">暂无预警</div>
              <div
                v-for="item in warnings"
                :key="`${item.taskId}-${item.year}`"
                class="scroll-item"
                :class="warningLevel(item) === 'RED' ? 'is-red' : 'is-orange'"
              >
                <span class="scroll-dot" />
                <span class="scroll-text" :title="warningText(item)">
                  {{ warningText(item) }}
                </span>
              </div>
            </div>
          </div>

          <div class="panel panel-activity">
            <i class="panel-corner panel-corner-tr" /><i class="panel-corner panel-corner-bl" />
            <div class="panel-title">最近动态</div>
            <div
              class="scroll-list"
              @mouseenter="activityPaused = true"
              @mouseleave="activityPaused = false"
            >
              <div v-if="activities.length === 0" class="panel-empty">暂无动态</div>
              <div v-for="(item, index) in activities" :key="`${item.time}-${index}`" class="scroll-item is-blue">
                <span class="scroll-dot" />
                <span class="scroll-text" :title="activityText(item)">{{ activityText(item) }}</span>
                <span class="scroll-time">{{ item.time.slice(5, 16).replace('T', ' ') }}</span>
              </div>
            </div>
          </div>
        </section>
      </main>

      <!-- ⑤ A 级指标下钻抽屉（点击指标条形触发，ESC / 遮罩 / 按钮关闭） -->
      <div v-if="drillVisible" class="drill-overlay" @click.self="closeIndicatorDrill">
        <aside class="drill-drawer">
          <header class="drill-head">
            <div class="drill-title">{{ drillIndicator?.name ?? '' }} · 指标下钻</div>
            <button class="drill-close" type="button" aria-label="关闭" @click="closeIndicatorDrill">✕</button>
          </header>
          <div v-if="drillIndicator" class="drill-summary">
            目标 <span class="num">{{ formatCount(drillIndicator.taskCount) }}</span> · 完成
            <span class="num">{{ formatCount(drillIndicator.achievedCount) }}</span> · 当年完成率
            <span class="num">{{ formatPercent(drillIndicator.completionRate) }}</span> · 五年累计
            <span class="num">{{ formatPercent(drillIndicator.cumulativeRate) }}</span>
          </div>
          <div class="drill-body">
            <div v-if="drillLoading" class="panel-empty">加载中…</div>
            <template v-else>
              <div v-if="drillRows.length === 0" class="panel-empty">暂无数据</div>
              <div
                v-for="row in drillRows"
                :key="`${row.node.level}-${row.node.id}-${row.node.taskId ?? ''}`"
                class="drill-row"
                :style="{ paddingLeft: `${12 + row.depth * 22}px` }"
              >
                <span class="drill-dot" :style="{ background: achieveDotColor(row.node) }" />
                <span class="drill-level" :class="`drill-level-${row.node.level.toLowerCase()}`">
                  {{ levelLabel(row.node.level) }}
                </span>
                <span class="drill-name" :title="`${row.node.code} ${row.node.name}`">
                  {{ row.node.code }} {{ row.node.name }}
                </span>
                <span v-if="row.node.unit?.name" class="drill-unit">{{ row.node.unit.name }}</span>
                <el-tag v-if="row.node.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
                <span class="drill-value num">{{ nodeValueText(row.node) }}</span>
                <span class="drill-rate num" title="当年完成率">{{ formatPercent(row.node.completionRate) }}</span>
                <span class="drill-rate num drill-rate-cum" title="五年累计完成率">
                  {{ formatPercent(row.node.cumulativeRate ?? null) }}
                </span>
              </div>
            </template>
          </div>
        </aside>
      </div>

      <!-- 首屏整体 loading（品牌 + 进度条，UI 规范第 8 章） -->
      <div v-if="firstLoading" class="screen-loading">
        <div class="loading-brand">五年发展规划指标监测</div>
        <div class="loading-bar"><div class="loading-bar-inner" /></div>
        <div class="loading-text">数据加载中…</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.screen-viewport {
  position: fixed;
  inset: 0;
  overflow: hidden;
  background: radial-gradient(ellipse 120% 90% at 50% 20%, #0a1c46 0%, #050d24 70%);
}

.screen-canvas {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 1920px;
  height: 1080px;
  transform-origin: center center;
  color: #e6eeff;
  font-family: var(--app-font-family);
}

.num {
  font-variant-numeric: tabular-nums;
  font-family: var(--app-font-number);
}

/* ---------- 顶部标题栏 ---------- */
.screen-header {
  position: relative;
  height: 96px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

.header-left {
  width: 360px;
  flex-shrink: 0;
}

.header-clock {
  font-size: 18px;
  color: #e6eeff;
  font-variant-numeric: tabular-nums;
}

.header-progress {
  margin-top: 4px;
  font-size: 14px;
  color: #8fa7d6;
}

.screen-title {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  margin: 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 44px;
  letter-spacing: 2px;
  white-space: nowrap;
  color: #e6eeff;
  text-shadow: 0 0 24px rgba(61, 214, 245, 0.35);
}

.header-right {
  width: 560px;
  margin-left: auto;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-shrink: 0;
}

.header-select {
  width: 220px;
  flex-shrink: 0;
}

.year-select {
  width: 110px;
}

.header-select :deep(.el-select__wrapper) {
  height: 32px;
  min-height: 32px;
  padding: 0 10px;
  box-sizing: border-box;
}

.header-select :deep(.el-select__selected-item) {
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.header-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  flex-shrink: 0;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
  color: #8fa7d6;
  background: rgba(16, 42, 100, 0.45);
  border: 1px solid rgba(79, 139, 255, 0.35);
  border-radius: 4px;
  cursor: pointer;
}

.header-btn .el-icon,
.header-btn svg {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.header-btn .el-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}

.header-btn svg {
  display: block;
}

.header-btn:hover {
  color: #3dd6f5;
  border-color: #3dd6f5;
}

.header-refresh {
  position: absolute;
  right: 24px;
  bottom: 4px;
  font-size: 11px;
  line-height: 16px;
  color: #8fa7d6;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.header-refresh.is-failed {
  color: #ff5b5b;
}

.fail-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 50%;
  background: #ff5b5b;
}

/* ---------- 三列布局（9.1） ---------- */
.screen-main {
  display: grid;
  grid-template-columns: 460px minmax(0, 1fr) 460px;
  grid-template-rows: minmax(0, 1fr);
  gap: 20px;
  height: calc(1080px - 96px);
  padding: 0 24px 24px;
  box-sizing: border-box;
}

.col {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
  min-height: 0;
}

/* ---------- 面板 ---------- */
.panel {
  position: relative;
  background: rgba(16, 42, 100, 0.45);
  border: 1px solid rgba(79, 139, 255, 0.35);
  border-radius: 4px;
  padding: 16px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 四角 2px 高亮角标 */
.panel::before,
.panel::after {
  content: '';
  position: absolute;
  width: 10px;
  height: 10px;
  border-color: #3dd6f5;
  border-style: solid;
}

.panel::before {
  left: -1px;
  top: -1px;
  border-width: 2px 0 0 2px;
}

.panel::after {
  right: -1px;
  bottom: -1px;
  border-width: 0 2px 2px 0;
}

/* 右上 / 左下两个 2px 高亮角标（与 ::before 左上、::after 右下合成四角） */
.panel-corner {
  position: absolute;
  width: 10px;
  height: 10px;
  border-color: #3dd6f5;
  border-style: solid;
  pointer-events: none;
}

.panel-corner-tr {
  right: -1px;
  top: -1px;
  border-width: 2px 2px 0 0;
}

.panel-corner-bl {
  left: -1px;
  bottom: -1px;
  border-width: 0 0 2px 2px;
}

.panel-title {
  position: relative;
  font-size: 20px;
  font-weight: 600;
  line-height: 26px;
  color: #e6eeff;
  padding-left: 12px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.panel-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 4px;
  width: 4px;
  background: #3dd6f5;
  border-radius: 2px;
}

.panel-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8fa7d6;
  font-size: 14px;
}

.panel-chart {
  flex: 1;
  min-height: 0;
  width: 100%;
}

/* 左列高度分配 */
.panel-overview {
  height: 340px;
}

.panel-nature {
  height: 300px;
}

.panel-adjust {
  flex: 1;
}

/* 中列高度分配 */
.panel-core {
  height: 116px;
  flex-direction: row;
  align-items: center;
  justify-content: space-around;
}

.panel-indicator {
  flex: 1.1;
  min-height: 0;
}

.panel-trend {
  flex: 1;
  min-height: 0;
}

/* 右列高度分配 */
.panel-rank {
  height: 470px;
}

.panel-warn {
  flex: 1.15;
  min-height: 0;
}

.panel-activity {
  flex: 1;
  min-height: 0;
}

/* ---------- ① 总体完成情况 ---------- */
.gauge-box {
  height: 180px;
  width: 100%;
  flex-shrink: 0;
}

.dual-progress {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.dp-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dp-label {
  width: 64px;
  font-size: 14px;
  color: #8fa7d6;
  flex-shrink: 0;
}

.dp-track {
  flex: 1;
  height: 10px;
  border-radius: 5px;
  background: rgba(79, 139, 255, 0.15);
  overflow: hidden;
}

.dp-fill {
  height: 100%;
  border-radius: 5px;
  transition: width 800ms ease;
}

.dp-fill-cyan {
  background: #3dd6f5;
}

.dp-fill-orange {
  background: #ffb547;
}

.dp-fill-red {
  background: #ff5b5b;
}

.dp-fill-green {
  background: #3ddc97;
}

.dp-value {
  width: 64px;
  text-align: right;
  font-size: 14px;
  color: #e6eeff;
  flex-shrink: 0;
}

/* 完成进度 vs 时间进度：▲ 领先（绿）/ ▼ 落后（红），附差值百分点 */
.dp-delta {
  width: 66px;
  flex-shrink: 0;
  font-size: 13px;
  white-space: nowrap;
}

.dp-delta.is-up {
  color: #3ddc97;
}

.dp-delta.is-down {
  color: #ff5b5b;
}

.dp-delta-placeholder {
  visibility: hidden;
}

/* ---------- ② 约束性 / 发展性 ---------- */
.nature-row {
  margin-bottom: 20px;
}

.nature-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.nature-tag {
  font-size: 14px;
  padding: 2px 10px;
  border-radius: 10px;
}

.nature-tag-danger {
  color: #ff5b5b;
  border: 1px solid rgba(255, 91, 91, 0.5);
}

.nature-tag-teal {
  color: #3ddc97;
  border: 1px solid rgba(61, 220, 151, 0.5);
}

.nature-num {
  font-size: 32px;
  font-weight: 600;
  color: #3dd6f5;
}

.nature-total {
  font-size: 16px;
  color: #8fa7d6;
}

/* ---------- ③ 调整统计 ---------- */
.adjust-grid {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  align-content: center;
}

.adjust-item {
  text-align: center;
}

.adjust-num {
  font-size: 48px;
  font-weight: 600;
  color: #3dd6f5;
  line-height: 56px;
}

.adjust-label {
  margin-top: 4px;
  font-size: 14px;
  color: #8fa7d6;
}

/* ---------- ④ 核心数字条 ---------- */
.core-item {
  text-align: center;
}

.core-num {
  font-size: 48px;
  font-weight: 600;
  line-height: 56px;
  color: #3dd6f5;
}

.core-label {
  font-size: 14px;
  color: #8fa7d6;
}

.text-orange {
  color: #ffb547;
}

.text-green {
  color: #3ddc97;
}

/* ---------- ⑧⑨ 滚动列表 ---------- */
.scroll-list {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.scroll-item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 10px;
  border-radius: 4px;
  background: rgba(79, 139, 255, 0.08);
  flex-shrink: 0;
  font-size: 14px;
  color: #e6eeff;
}

.scroll-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.scroll-item.is-red .scroll-dot {
  background: #ff5b5b;
}

.scroll-item.is-red {
  border-left: 3px solid #ff5b5b;
}

.scroll-item.is-orange .scroll-dot {
  background: #ffb547;
}

.scroll-item.is-orange {
  border-left: 3px solid #ffb547;
}

.scroll-item.is-blue .scroll-dot {
  background: #3dd6f5;
}

.scroll-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scroll-time {
  flex-shrink: 0;
  font-size: 12px;
  color: #8fa7d6;
  font-variant-numeric: tabular-nums;
}

/* ---------- ⑤ A 级指标下钻抽屉 ---------- */
.drill-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  justify-content: flex-end;
  background: rgba(5, 13, 36, 0.6);
}

.drill-drawer {
  width: 620px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: rgba(10, 28, 70, 0.96);
  border-left: 1px solid rgba(79, 139, 255, 0.35);
  box-shadow: -8px 0 32px rgba(0, 0, 0, 0.45);
  animation: drill-in 240ms ease;
}

@keyframes drill-in {
  from {
    transform: translateX(40px);
    opacity: 0;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}

.drill-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(79, 139, 255, 0.2);
  flex-shrink: 0;
}

.drill-title {
  font-size: 18px;
  font-weight: 600;
  color: #e6eeff;
}

.drill-close {
  width: 32px;
  height: 32px;
  font-size: 15px;
  line-height: 1;
  color: #8fa7d6;
  background: rgba(16, 42, 100, 0.45);
  border: 1px solid rgba(79, 139, 255, 0.35);
  border-radius: 4px;
  cursor: pointer;
}

.drill-close:hover {
  color: #3dd6f5;
  border-color: #3dd6f5;
}

.drill-summary {
  padding: 10px 20px;
  font-size: 14px;
  color: #8fa7d6;
  border-bottom: 1px solid rgba(79, 139, 255, 0.12);
  flex-shrink: 0;
}

.drill-summary .num {
  color: #3dd6f5;
}

.drill-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 0;
}

.drill-body::-webkit-scrollbar {
  width: 6px;
}

.drill-body::-webkit-scrollbar-thumb {
  background: rgba(79, 139, 255, 0.3);
  border-radius: 3px;
}

.drill-row {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding-right: 16px;
  font-size: 14px;
  color: #e6eeff;
}

.drill-row:hover {
  background: rgba(79, 139, 255, 0.08);
}

.drill-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.drill-level {
  flex-shrink: 0;
  font-size: 12px;
  padding: 1px 6px;
  border-radius: 3px;
  border: 1px solid rgba(79, 139, 255, 0.35);
  color: #4f8bff;
}

.drill-level-a {
  color: #ffb547;
  border-color: rgba(255, 181, 71, 0.5);
}

.drill-level-a_plus {
  color: #3dd6f5;
  border-color: rgba(61, 214, 245, 0.5);
}

.drill-level-b {
  color: #4f8bff;
  border-color: rgba(79, 139, 255, 0.5);
}

.drill-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drill-unit {
  flex-shrink: 0;
  font-size: 12px;
  color: #8fa7d6;
}

.drill-value {
  flex-shrink: 0;
  font-size: 13px;
  color: #8fa7d6;
}

.drill-rate {
  flex-shrink: 0;
  width: 64px;
  text-align: right;
  color: #3dd6f5;
}

.drill-rate-cum {
  color: #ffb547;
  font-weight: 600;
}

/* ---------- 首屏 loading ---------- */
.screen-loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  background: radial-gradient(ellipse 120% 90% at 50% 20%, #0a1c46 0%, #050d24 70%);
  z-index: 10;
}

.loading-brand {
  font-size: 36px;
  font-weight: 600;
  letter-spacing: 4px;
  color: #e6eeff;
  text-shadow: 0 0 24px rgba(61, 214, 245, 0.35);
}

.loading-bar {
  width: 320px;
  height: 6px;
  border-radius: 3px;
  background: rgba(79, 139, 255, 0.15);
  overflow: hidden;
}

.loading-bar-inner {
  height: 100%;
  width: 40%;
  border-radius: 3px;
  background: #3dd6f5;
  animation: loading-slide 1.2s ease-in-out infinite;
}

@keyframes loading-slide {
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(320px);
  }
}

.loading-text {
  font-size: 14px;
  color: #8fa7d6;
}

/* 大屏内的 Element Plus 下拉（深色适配） */
.header-select :deep(.el-select__wrapper) {
  background: rgba(16, 42, 100, 0.45);
  box-shadow: 0 0 0 1px rgba(79, 139, 255, 0.35) inset;
}

.header-select :deep(.el-select__selected-item),
.header-select :deep(.el-select__placeholder) {
  color: #e6eeff;
}
</style>
