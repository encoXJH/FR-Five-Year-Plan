<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { CustomSeriesRenderItem, EChartsOption } from 'echarts'
import { useEChart } from '@/utils/chart'
import { useUserStore } from '@/stores/user'
import { GANTT_TONE_COLORS, ganttTone } from '@/utils/gantt'
import {
  GANTT_ROW_HEIGHT,
  flattenGanttNodes,
  layoutDependencies,
  snapTimestampToYear,
  visibleGanttWindow,
  yearEnd,
  yearStart,
} from '@/utils/ganttLayout'
import { formatDate, formatDateTime, formatNumber, formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import { enumLabel } from '@/utils/enumLabels'
import type { GanttDependency, GanttMilestone, GanttNode, GanttYearValue } from '@/types/gantt'

const props = withDefaults(defineProps<{
  years: number[]
  nodes: GanttNode[]
  milestones?: GanttMilestone[]
  dependencies?: GanttDependency[]
  criticalTaskIds?: string[]
  criticalDependencyIds?: string[]
  readonly?: boolean
}>(), {
  milestones: () => [], dependencies: () => [], criticalTaskIds: () => [], criticalDependencyIds: () => [], readonly: false,
})

const emit = defineEmits<{
  select: [node: GanttNode]
  segmentDrag: [payload: { node: GanttNode; fromYear: number; toYear: number }]
  milestoneDrag: [payload: { milestone: GanttMilestone; toYear: number }]
  toggle: [node: GanttNode]
}>()

const userStore = useUserStore()
const chart = useEChart()
const collapsed = ref(new Set<string>())
const scrollTop = ref(0)
const viewportHeight = ref(560)
const zoom = ref(1)
const bodyRef = ref<HTMLDivElement | null>(null)
const rangeStart = ref(0)
const rangeEnd = ref(0)
let frame = 0
let drag: { node: GanttNode; fromYear: number; milestone?: GanttMilestone } | null = null

const rows = computed(() => flattenGanttNodes(props.nodes, collapsed.value))
const virtual = computed(() => visibleGanttWindow(scrollTop.value, viewportHeight.value, rows.value.length))
const visibleRows = computed(() => rows.value.slice(virtual.value.start, virtual.value.end))
const totalHeight = computed(() => rows.value.length * GANTT_ROW_HEIGHT)
const bodyHeight = computed(() => Math.min(viewportHeight.value, Math.max(190, totalHeight.value + 34)))
const criticalTasks = computed(() => new Set(props.criticalTaskIds))
const criticalDependencies = computed(() => new Set(props.criticalDependencyIds))
const minTime = computed(() => yearStart(props.years[0] ?? new Date().getFullYear()))
const maxTime = computed(() => yearEnd(props.years.at(-1) ?? new Date().getFullYear()))
const span = computed(() => Math.max(1, rangeEnd.value - rangeStart.value))

interface BarMeta {
  start: number
  end: number
  mode: 'bar' | 'milestone'
  startYear: number
  endYear: number
  tone: keyof typeof GANTT_TONE_COLORS
  rate: number | null
  label: string
  detail: string
}

interface Datum {
  value: [number, number, number]
  node: GanttNode
  bar?: BarMeta
  milestone?: GanttMilestone
  kind: 'bar' | 'milestone'
}

const milestonesByParent = computed(() => {
  const result = new Map<string, GanttMilestone[]>()
  for (const milestone of props.milestones) {
    const parent = milestone.taskId ?? milestone.indicatorId
    if (!parent) continue
    const list = result.get(parent) ?? []
    list.push(milestone)
    result.set(parent, list)
  }
  return result
})

function isActiveYear(node: GanttNode, item: GanttYearValue): boolean {
  if (node.caliber === 'BOOLEAN') return item.target === true || item.actualValue === 1
  const target = item.target
  const hasTarget = target !== null && target !== undefined && Number(target) !== 0
  const hasActual = item.actualValue !== null || item.actualTotal !== null || item.actualNumerator !== null
  return hasTarget || hasActual
}

function targetSummary(node: GanttNode, active: GanttYearValue[]): string {
  if (node.caliber === 'RATIO') return '占比汇总口径'
  const numeric = active.map((item) => Number(item.target)).filter((value) => Number.isFinite(value))
  if (!numeric.length) return '暂无目标'
  const value = node.caliber === 'INCREMENT' ? numeric.reduce((sum, item) => sum + item, 0) : numeric[numeric.length - 1]
  const unit = node.unitLabel && ['INCREMENT', 'STOCK', 'GROWTH'].includes(node.caliber ?? '') ? ` ${node.unitLabel}` : ''
  return `目标 ${formatTargetValue(value, node.caliber)}${unit}`
}

const barByNode = computed(() => {
  const result = new Map<string, BarMeta>()
  for (const node of props.nodes) {
    const active = node.years
      .filter((item) => props.years.includes(item.year) && isActiveYear(node, item))
      .sort((a, b) => a.year - b.year)
    if (!active.length) continue
    const first = active[0]
    const last = active[active.length - 1]
    if (node.caliber === 'BOOLEAN') {
      const targetYear = active.find((item) => item.target === true)?.year ?? last.year
      const tone = targetYear === last.year && last.actualValue === 1 ? 'success' : 'primary'
      result.set(node.id, {
        start: yearStart(targetYear) + (yearEnd(targetYear) - yearStart(targetYear)) / 2,
        end: yearStart(targetYear) + (yearEnd(targetYear) - yearStart(targetYear)) / 2,
        mode: 'milestone',
        startYear: targetYear,
        endYear: targetYear,
        tone,
        rate: last.actualValue === 1 ? 1 : null,
        label: `${targetYear} 年完成`,
        detail: last.actualValue === 1 ? '已完成' : '未完成',
      })
      continue
    }
    const rates = active.map((item) => item.completionRate).filter((value): value is number => typeof value === 'number')
    const rate = typeof node.cumulativeRate === 'number'
      ? node.cumulativeRate
      : rates.length
        ? rates.reduce((sum, item) => sum + item, 0) / rates.length
        : null
    const latestTone = ganttTone(last.status)
    result.set(node.id, {
      start: yearStart(first.year),
      end: yearEnd(last.year),
      mode: 'bar',
      startYear: first.year,
      endYear: last.year,
      tone: latestTone === 'muted' ? 'primary' : latestTone,
      rate,
      label: rate === null ? '尚未填报' : `完成率 ${formatPercent(rate)}`,
      detail: targetSummary(node, active),
    })
  }
  return result
})

const chartData = computed<Datum[]>(() => visibleRows.value.flatMap((node, rowIndex) => {
  const items: Datum[] = []
  const bar = barByNode.value.get(node.id)
  if (bar) items.push({ value: [bar.start, rowIndex, bar.end], node, bar, kind: 'bar' })
  for (const milestone of milestonesByParent.value.get(node.id) ?? []) {
    const year = milestone.relatedYear ?? Number(milestone.dueDate?.slice(0, 4))
    if (!props.years.includes(year)) continue
    const center = yearStart(year) + (yearEnd(year) - yearStart(year)) / 2
    items.push({ value: [center, rowIndex, center], node, milestone, kind: 'milestone' })
  }
  return items
}))

const today = Date.now()
const showToday = computed(() => today >= minTime.value && today <= maxTime.value && today >= rangeStart.value && today <= rangeEnd.value)

const yearTicks = computed(() => {
  if (rangeEnd.value <= rangeStart.value) return []
  return props.years.flatMap((year) => {
    const start = yearStart(year)
    const end = yearEnd(year)
    if (end <= rangeStart.value || start >= rangeEnd.value) return []
    const left = ((Math.max(start, rangeStart.value) - rangeStart.value) / span.value) * 100
    const right = ((Math.min(end, rangeEnd.value) - rangeStart.value) / span.value) * 100
    return [{ year, left, center: (left + right) / 2 }]
  })
})

function textOf(bar: BarMeta, width: number): string[] {
  if (bar.mode === 'milestone') return []
  if (width > 118) return [bar.label, bar.detail]
  if (width > 64) return [bar.label]
  return []
}

function renderItem(data: Datum[]): CustomSeriesRenderItem {
  return (params, api) => {
    const datum = data[params.dataIndex]
    if (!datum) return null
    const start = api.coord([datum.value[0], datum.value[1]])
    const end = api.coord([datum.value[2], datum.value[1]])
    const size = api.size?.([0, 1])
    const rowHeight = Array.isArray(size) ? size[1] : (size ?? GANTT_ROW_HEIGHT)
    const height = Math.min(30, rowHeight - 12)
    if (datum.kind === 'milestone' && datum.milestone) {
      const done = ganttTone(datum.milestone.status) === 'success'
      return {
        type: 'polygon',
        shape: { points: [[start[0], start[1] - 9], [start[0] + 9, start[1]], [start[0], start[1] + 9], [start[0] - 9, start[1]]] },
        style: { fill: done ? GANTT_TONE_COLORS.success : GANTT_TONE_COLORS.warning, stroke: '#fff', lineWidth: 2 },
      }
    }
    const bar = datum.bar!
    if (bar.mode === 'milestone') {
      return {
        type: 'polygon',
        shape: { points: [[start[0], start[1] - 8], [start[0] + 8, start[1]], [start[0], start[1] + 8], [start[0] - 8, start[1]]] },
        style: { fill: GANTT_TONE_COLORS[bar.tone], stroke: '#fff', lineWidth: 2 },
      }
    }
    const width = Math.max(3, end[0] - start[0] - 4)
    const critical = criticalTasks.value.has(datum.node.id)
    const rate = Math.max(0, Math.min(1, bar.rate ?? 0))
    const children: any[] = [
      { type: 'rect', shape: { x: start[0] + 2, y: start[1] - height / 2, width, height, r: 5 }, style: { fill: '#E9EFF9' } },
    ]
    if (rate > 0) {
      children.push({ type: 'rect', shape: { x: start[0] + 2, y: start[1] - height / 2, width: Math.max(3, width * rate), height, r: 5 }, style: { fill: GANTT_TONE_COLORS[bar.tone], opacity: 0.9 } })
    }
    if (critical) {
      children.push({ type: 'rect', shape: { x: start[0] + 2, y: start[1] - height / 2, width, height, r: 5 }, style: { fill: 'transparent', stroke: '#D4380D', lineWidth: 2 } })
    }
    const lines = textOf(bar, width)
    if (lines[0]) children.push({ type: 'text', x: start[0] + 9, y: start[1] - 4, style: { text: lines[0], fill: '#2B3A55', fontSize: 12, fontWeight: 600 } })
    if (lines[1]) children.push({ type: 'text', x: start[0] + 9, y: start[1] + 10, style: { text: lines[1], fill: '#5A6B87', fontSize: 11 } })
    return { type: 'group', children }
  }
}

function dependencyOption(): any {
  const dependencies = layoutDependencies(props.dependencies, visibleRows.value, virtual.value.start, criticalDependencies.value)
  return {
    type: 'custom' as const,
    silent: true,
    z: 1,
    data: dependencies,
    renderItem: (params: any, api: any) => {
      const dependency = dependencies[params.dataIndex]
      if (!dependency) return null
      const nodeById = new Map(props.nodes.map((node) => [node.id, node]))
      const fromNode = nodeById.get(dependency.predecessorTaskId)
      const toNode = nodeById.get(dependency.successorTaskId)
      const fromBar = fromNode ? barByNode.value.get(fromNode.id) : undefined
      const toBar = toNode ? barByNode.value.get(toNode.id) : undefined
      if (!fromBar || !toBar) return null
      const from = api.coord([fromBar.end, dependency.fromRow - virtual.value.start])
      const to = api.coord([toBar.start, dependency.toRow - virtual.value.start])
      const middle = from[0] + Math.max(12, (to[0] - from[0]) / 2)
      return { type: 'group', children: [
        { type: 'polyline', shape: { points: [from, [middle, from[1]], [middle, to[1]], to] }, style: { stroke: dependency.critical ? '#D4380D' : '#7A8BA8', lineWidth: dependency.critical ? 3 : 1.5, fill: null } },
        { type: 'polygon', shape: { points: [[to[0], to[1]], [to[0] - 8, to[1] - 4], [to[0] - 8, to[1] + 4]] }, style: { fill: dependency.critical ? '#D4380D' : '#7A8BA8' } },
      ] }
    },
  }
}

function todayOption(): any {
  return {
    type: 'custom' as const,
    silent: true,
    z: 3,
    data: [today],
    renderItem: (_params: any, api: any) => {
      const point = api.coord([today, 0])
      const height = api.getHeight()
      return {
        type: 'group',
        children: [
          { type: 'line', shape: { x1: point[0], y1: 0, x2: point[0], y2: height }, style: { stroke: '#D4380D', lineWidth: 1, lineDash: [5, 4] } },
          { type: 'text', x: point[0] + 5, y: 10, style: { text: '今天', fill: '#D4380D', fontSize: 11 } },
        ],
      }
    },
  }
}

function render() {
  if (!props.years.length || !visibleRows.value.length) return
  const option: EChartsOption = {
    animation: false,
    tooltip: { trigger: 'item', confine: true, formatter: (params: any) => tooltip(params.data as Datum) },
    grid: { left: 12, right: 22, top: 0, bottom: 0, containLabel: false },
    xAxis: {
      type: 'time',
      min: rangeStart.value,
      max: rangeEnd.value,
      axisLabel: { show: false },
      axisTick: { show: false },
      axisLine: { show: false },
      splitLine: { show: true, lineStyle: { color: '#EDF0F5' } },
    },
    yAxis: { type: 'category', inverse: true, data: visibleRows.value.map((node) => node.id), axisLabel: { show: false }, axisTick: { show: false }, axisLine: { show: false } },
    series: [
      dependencyOption(),
      { type: 'custom', z: 2, renderItem: renderItem(chartData.value), data: chartData.value },
      ...(showToday.value ? [todayOption()] : []),
    ],
  }
  chart.setOption(option, true)
}

function tooltip(datum: Datum): string {
  const nodeName = `${datum.node.code} ${datum.node.name}`
  if (datum.kind === 'milestone' && datum.milestone) {
    return [`<strong>${nodeName}</strong>`, `里程碑：${datum.milestone.name}`, `计划日期：${formatDate(datum.milestone.dueDate)}`, `状态：${enumLabel('MilestoneStatus', datum.milestone.status, userStore.enums)}`, `实际完成：${formatDateTime(datum.milestone.completedAt)}`].join('<br/>')
  }
  const bar = datum.bar!
  const lines = [`<strong>${nodeName}</strong>`, `${bar.startYear}–${bar.endYear} 年`, bar.label, bar.detail]
  if (bar.mode === 'bar') {
    const active = datum.node.years.filter((item) => props.years.includes(item.year) && isActiveYear(datum.node, item))
    for (const item of active.slice(0, 6)) {
      lines.push(`${item.year} 年：目标 ${formatTargetValue(item.target, datum.node.caliber)} · 实际 ${formatTargetValue(item.actualValue, datum.node.caliber)} · ${formatPercent(item.completionRate)}`)
    }
    if (active.length > 6) lines.push(`其余 ${active.length - 6} 个年度数据略`)
    if (datum.node.caliber === 'RATIO' && active.length) {
      const last = active[active.length - 1]
      lines.push(`分子：${formatNumber(last.actualNumerator)} · 分母：${formatNumber(last.actualDenominator)}`)
    }
  }
  return lines.join('<br/>')
}

function scheduleRender() {
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(() => void nextTick(render))
}

function onScroll() {
  scrollTop.value = bodyRef.value?.scrollTop ?? 0
  scheduleRender()
}

function toggleNode(node: GanttNode) {
  if (node.kind !== 'indicator') return
  const next = new Set(collapsed.value)
  if (next.has(node.id)) next.delete(node.id)
  else next.add(node.id)
  collapsed.value = next
  emit('toggle', node)
  scheduleRender()
}

function setRange(start: number, end: number) {
  const totalMin = minTime.value
  const totalMax = maxTime.value
  let nextStart = start
  let nextEnd = end
  if (nextEnd - nextStart >= totalMax - totalMin) {
    nextStart = totalMin
    nextEnd = totalMax
  } else {
    if (nextStart < totalMin) {
      nextEnd += totalMin - nextStart
      nextStart = totalMin
    }
    if (nextEnd > totalMax) {
      nextStart -= nextEnd - totalMax
      nextEnd = totalMax
    }
  }
  rangeStart.value = Math.max(totalMin, nextStart)
  rangeEnd.value = Math.min(totalMax, nextEnd)
}

function focusCenter(): number {
  const bars = [...barByNode.value.values()]
  if (!bars.length) return (minTime.value + maxTime.value) / 2
  const total = bars.reduce((sum, bar) => sum + (yearStart(bar.startYear) + yearEnd(bar.endYear)) / 2, 0)
  return total / bars.length
}

function setZoom(next: number) {
  const level = Math.max(1, Math.min(8, next))
  const center = focusCenter()
  const half = (maxTime.value - minTime.value) / level / 2
  zoom.value = level
  setRange(center - half, center + half)
  scheduleRender()
}

function pan(direction: -1 | 1) {
  const current = rangeEnd.value - rangeStart.value
  const step = (current / 2) * direction
  setRange(rangeStart.value + step, rangeEnd.value + step)
  zoom.value = Math.max(1, Math.round((maxTime.value - minTime.value) / Math.max(1, rangeEnd.value - rangeStart.value)))
  scheduleRender()
}

function resetZoom() {
  zoom.value = 1
  setRange(minTime.value, maxTime.value)
  scheduleRender()
}

function pointerMove(event: PointerEvent) {
  if (!drag) return
  const instance = chart.chart.value
  if (!instance) return
  const rect = instance.getDom().getBoundingClientRect()
  const point = instance.convertFromPixel({ seriesIndex: 1 }, [event.clientX - rect.left, event.clientY - rect.top])
  const year = snapTimestampToYear(Number(point?.[0]), props.years)
  if (year !== null) document.body.dataset.ganttDropYear = String(year)
}

function pointerUp() {
  if (!drag) return
  const value = Number(document.body.dataset.ganttDropYear)
  delete document.body.dataset.ganttDropYear
  const current = drag
  drag = null
  document.body.style.cursor = ''
  if (!props.years.includes(value) || value === current.fromYear) return
  if (current.milestone) emit('milestoneDrag', { milestone: current.milestone, toYear: value })
  else emit('segmentDrag', { node: current.node, fromYear: current.fromYear, toYear: value })
}

watch(() => chart.chart.value, (instance) => {
  if (!instance) return
  instance.off('mousedown')
  instance.on('mousedown', (params: any) => {
    const datum = params.data as Datum | undefined
    if (!datum || props.readonly) return
    if (datum.kind === 'milestone' && datum.milestone) drag = { node: datum.node, fromYear: datum.milestone.relatedYear ?? 0, milestone: datum.milestone }
    else if (datum.bar?.mode === 'bar' && datum.node.kind === 'task') drag = { node: datum.node, fromYear: datum.bar.startYear }
    if (drag) document.body.style.cursor = 'grabbing'
  })
})

watch([rows, zoom, () => props.dependencies, () => props.milestones], scheduleRender, { immediate: true })
watch(() => props.years, () => {
  setRange(minTime.value, maxTime.value)
  scheduleRender()
}, { immediate: true })

window.addEventListener('pointermove', pointerMove)
window.addEventListener('pointerup', pointerUp)
onBeforeUnmount(() => {
  cancelAnimationFrame(frame)
  window.removeEventListener('pointermove', pointerMove)
  window.removeEventListener('pointerup', pointerUp)
})

const legend = [
  ['进行中', 'primary'], ['达标 / 已完成', 'success'], ['未达标 / 滞后', 'danger'],
  ['待审核', 'warning'], ['调整 / 延期', 'adjust'], ['未来 / 无数据', 'muted'],
] as const
</script>

<template>
  <div class="indicator-gantt">
    <div class="gantt-toolbar">
      <div class="gantt-legend" aria-label="甘特图状态图例">
        <span v-for="item in legend" :key="item[1]"><i :style="{ backgroundColor: GANTT_TONE_COLORS[item[1]] }" />{{ item[0] }}</span>
        <span v-if="dependencies.length"><i class="critical-line" />关键路径</span>
      </div>
      <div class="zoom-actions" aria-label="时间轴缩放">
        <el-button size="small" aria-label="向左平移时间轴" :disabled="rangeStart <= minTime" @click="pan(-1)">←</el-button>
        <el-button size="small" :disabled="zoom <= 1" @click="setZoom(zoom / 2)">缩小</el-button>
        <span>{{ zoom }}×</span>
        <el-button size="small" :disabled="zoom >= 8" @click="setZoom(zoom * 2)">放大</el-button>
        <el-button size="small" aria-label="向右平移时间轴" :disabled="rangeEnd >= maxTime" @click="pan(1)">→</el-button>
        <el-button size="small" @click="resetZoom">全规划期</el-button>
      </div>
    </div>
    <el-empty v-if="!nodes.length || !years.length" :image-size="80" description="暂无甘特图数据" />
    <div v-else ref="bodyRef" class="gantt-body" :style="{ height: `${bodyHeight}px` }" @scroll="onScroll">
      <div class="gantt-scale">
        <div class="scale-label">任务 / 学院</div>
        <div class="scale-track">
          <i v-for="tick in yearTicks" :key="`line-${tick.year}`" class="scale-line" :style="{ left: `${tick.left}%` }" />
          <span v-for="tick in yearTicks" :key="tick.year" class="scale-tick" :style="{ left: `${tick.center}%` }">{{ tick.year }}</span>
        </div>
      </div>
      <div class="gantt-spacer" :style="{ height: `${totalHeight}px` }">
        <div class="gantt-window" :style="{ transform: `translateY(${virtual.offsetTop}px)`, height: `${(virtual.end - virtual.start) * GANTT_ROW_HEIGHT}px` }">
          <div class="gantt-labels">
            <button v-for="node in visibleRows" :key="node.id" type="button" class="gantt-label" :class="[node.kind, { critical: criticalTasks.has(node.id) }]" :title="`${node.code} ${node.name}`" @click="toggleNode(node); emit('select', node)">
              <span v-if="node.kind === 'indicator'" class="expand-icon">{{ collapsed.has(node.id) ? '▸' : '▾' }}</span>
              <span v-else class="task-indent" />
              <span class="label-copy">
                <span class="label-top">
                  <strong class="mono">{{ node.code }}</strong>
                  <em v-if="node.unitName" class="unit">{{ node.unitName }}</em>
                  <em v-else-if="!barByNode.has(node.id)" class="no-plan">未设目标</em>
                </span>
                <span class="label-name">{{ node.name }}</span>
              </span>
            </button>
          </div>
          <div :ref="chart.container" class="gantt-canvas" />
        </div>
      </div>
    </div>
    <p class="gantt-help">条形长度表示有目标或数据的年度区间，填充比例表示累计完成率；滚轮纵向浏览任务，授权用户可拖动年度条或里程碑发起调整。</p>
  </div>
</template>

<style scoped>
.indicator-gantt { --gantt-label-width: 320px; }
.gantt-toolbar { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; margin-bottom:12px; }
.gantt-legend { display:flex; flex-wrap:wrap; gap:8px 14px; color:#606266; font-size:12px; }
.gantt-legend span { display:inline-flex; align-items:center; gap:5px; white-space:nowrap; }
.gantt-legend i { width:10px; height:10px; border-radius:2px; }
.gantt-legend .critical-line { width:16px; height:3px; background:#D4380D; }
.zoom-actions { display:flex; align-items:center; gap:5px; flex:none; font-size:12px; }
.gantt-body { position:relative; overflow:auto; border:1px solid #E4E9F2; border-radius:6px; background:#fff; contain:layout paint; }
.gantt-scale { position:sticky; top:0; z-index:6; display:flex; height:34px; min-width:820px; background:#FAFBFD; border-bottom:1px solid #E4E9F2; }
.scale-label { position:sticky; left:0; z-index:7; width:var(--gantt-label-width); flex:none; display:flex; align-items:center; padding:0 12px; background:#FAFBFD; border-right:1px solid #E4E9F2; color:#909399; font-size:12px; }
.scale-track { position:relative; flex:1; margin:0 22px 0 12px; }
.scale-line { position:absolute; top:0; bottom:0; width:1px; background:#E8EDF5; }
.scale-tick { position:absolute; bottom:7px; transform:translateX(-50%); color:#5A6B87; font-size:12px; font-variant-numeric:tabular-nums; }
.gantt-spacer { position:relative; min-width:820px; }
.gantt-window { position:absolute; inset:0 0 auto; display:flex; }
.gantt-labels { width:var(--gantt-label-width); flex:none; position:sticky; left:0; z-index:4; background:#fff; box-shadow:1px 0 #E4E9F2; }
.gantt-label { height:64px; width:100%; display:flex; align-items:center; gap:5px; padding:6px 10px; border:0; border-bottom:1px solid #F0F2F5; background:#fff; text-align:left; cursor:pointer; color:#303133; }
.gantt-label.indicator { background:#F5F8FD; }
.gantt-label.critical { box-shadow:inset 3px 0 #D4380D; }
.gantt-label:focus-visible { outline:2px solid #2B5BC0; outline-offset:-2px; }
.expand-icon { color:#2B5BC0; font-size:16px; flex:none; width:14px; text-align:center; }
.task-indent { width:14px; flex:none; }
.label-copy { min-width:0; flex:1; display:flex; flex-direction:column; gap:3px; }
.label-top { display:flex; align-items:center; gap:6px; min-width:0; }
.label-top strong { font-size:12px; color:#2B5BC0; flex:none; }
.label-top .unit { flex:none; max-width:132px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding:1px 6px; border-radius:8px; background:#EDF2FC; color:#456; font-size:11px; font-style:normal; }
.label-top .no-plan { flex:none; padding:1px 6px; border-radius:8px; background:#F4F4F5; color:#909399; font-size:11px; font-style:normal; }
.label-name { display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; font-size:12px; line-height:16px; color:#303133; }
.gantt-canvas { flex:1; min-width:490px; height:100%; }
.gantt-help { margin:8px 0 0; color:#909399; font-size:12px; }
@media (max-width:768px) {
  .indicator-gantt { --gantt-label-width:190px; }
  .gantt-toolbar { flex-direction:column; }
  .gantt-scale, .gantt-spacer { min-width:700px; }
  .label-top .unit { max-width:88px; }
}
</style>
