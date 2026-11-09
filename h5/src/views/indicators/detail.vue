<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import PageState from '@/components/PageState.vue'
import TaskDetailSheet from '@/components/TaskDetailSheet.vue'
import IndicatorFormSheet from './components/IndicatorFormSheet.vue'
import { getIndicator } from '@/api/indicator'
import { getPlan } from '@/api/plan'
import { listAllTasks } from '@/api/task'
import { getIndicatorTrend } from '@/api/stats'
import { listMilestones } from '@/api/milestone'
import { getPlanGantt } from '@/api/gantt'
import { listDirections } from '@/api/direction'
import { useUserStore } from '@/stores/user'
import { formatPercent, formatDateTime } from '@/utils/format'
import {
  completionPercent,
  formatActualValue,
  formatTargetValue,
  hasQuantitativeFields,
} from '@/utils/indicator'
import { primaryTaskIndicator } from '@/utils/task'
import type { IndicatorDetail } from '@/types/indicator'
import type { PlanDetail } from '@/types/plan'
import type { IndicatorTrend } from '@/types/stats'
import type { TaskListItem } from '@/types/task'
import type { Milestone } from '@/types/milestone'
import type { PlanGantt } from '@/types/gantt'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const indicatorId = computed(() => route.params.id as string)
const loading = ref(false)
const indicator = ref<IndicatorDetail | null>(null)
const plan = ref<PlanDetail | null>(null)
const tasks = ref<TaskListItem[]>([])
const trend = ref<IndicatorTrend | null>(null)
const milestones = ref<Milestone[]>([])
const gantt = ref<PlanGantt | null>(null)
const directions = ref<{ id: string; name: string }[]>([])
const taskLoading = ref(false)

const quantitative = computed(() => {
  const detail = indicator.value
  return !!detail && hasQuantitativeFields(detail.level, detail.isQuantifiable)
})
const hasDistributedScope = computed(() => {
  const detail = indicator.value
  if (!detail) return false
  return detail.status === 'DISTRIBUTED'
    || detail.children.some((child) => child.status === 'DISTRIBUTED')
    || (detail.bSummaries ?? []).some((task) => task.isHistorical || task.status !== 'PENDING_DISTRIBUTION')
})
const isArchived = computed(() => plan.value?.status === 'ARCHIVED')

const years = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) list.push(year)
  return list
})

const canEdit = computed(() =>
  quantitative.value || indicator.value?.level === 'A'
    ? userStore.can('indicator:edit') && (indicator.value?.editable ?? true)
      && !hasDistributedScope.value && !isArchived.value
    : false,
)
const canApplyAdjustment = computed(() =>
  quantitative.value && userStore.can('adjustment:apply') && !isArchived.value,
)
const primaryAction = computed<'edit' | 'adjust' | null>(() => {
  if (!indicator.value || isArchived.value) return null
  if (hasDistributedScope.value) return canApplyAdjustment.value ? 'adjust' : null
  return canEdit.value ? 'edit' : null
})
const formVisible = ref(false)

function runPrimaryAction(): void {
  if (primaryAction.value === 'edit') {
    formVisible.value = true
    return
  }
  if (primaryAction.value !== 'adjust' || !indicator.value) return
  router.push({
    path: '/adjustments/apply',
    query: typeof route.query.taskId === 'string'
      ? { taskId: route.query.taskId }
      : { indicatorId: indicator.value.id },
  })
}

let loadSequence = 0

async function loadAll(): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  try {
    const detail = await getIndicator(indicatorId.value)
    if (sequence !== loadSequence) return
    if (detail.level === 'A_PLUS') {
      const parentId = detail.parent?.id ?? detail.parentId
      if (parentId) {
        await router.replace(`/indicators/${parentId}`)
        return
      }
    }
    indicator.value = detail
    const planDetail = await getPlan(detail.planId)
    if (sequence !== loadSequence) return
    plan.value = planDetail
    await Promise.all([loadTasks(detail.id), loadTrend(detail.id), loadMilestones(detail.id), loadGantt(detail)])
    if (userStore.roles.includes('ADMIN')) {
      directions.value = await listDirections(detail.planId)
        .then((list) => list.map((item) => ({ id: item.id, name: item.name })))
        .catch(() => [])
    }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function loadTasks(id: string): Promise<void> {
  taskLoading.value = true
  try {
    tasks.value = await listAllTasks({ indicatorId: id })
  } finally {
    taskLoading.value = false
  }
}

async function loadTrend(id: string): Promise<void> {
  try {
    trend.value = await getIndicatorTrend(id)
  } catch {
    trend.value = null
  }
}

async function loadMilestones(id: string): Promise<void> {
  try {
    milestones.value = await listMilestones({ indicatorId: id })
  } catch {
    milestones.value = []
  }
}

async function loadGantt(detail: IndicatorDetail): Promise<void> {
  if (!hasQuantitativeFields(detail.level, detail.isQuantifiable)) {
    gantt.value = null
    return
  }
  try {
    gantt.value = await getPlanGantt(detail.planId, detail.id)
  } catch {
    gantt.value = null
  }
}

function ganttYearCell(node: PlanGantt['nodes'][number], year: number): { label: string; tone: string } {
  const item = node.years.find((entry) => entry.year === year)
  if (!item) return { label: '', tone: 'none' }
  if (item.completionRate !== null && item.completionRate !== undefined) {
    return {
      label: formatPercent(item.completionRate),
      tone: item.completionRate >= 1 ? 'complete' : item.completionRate > 0 ? 'partial' : 'none',
    }
  }
  const target = item.target
  const label = typeof target === 'boolean' ? (target ? '是' : '') : (target === null ? '' : formatTargetValue(target, node.caliber))
  return { label, tone: target === null || target === false ? 'planned' : 'planned' }
}

function openGanttNode(node: PlanGantt['nodes'][number]): void {
  if (node.kind === 'task') openTask({ id: node.id } as TaskListItem)
}

const taskSheetVisible = ref(false)
const taskSheetId = ref<string | null>(null)

function openTask(task: TaskListItem): void {
  taskSheetId.value = task.id
  taskSheetVisible.value = true
}

function yearTaskRow(task: TaskListItem, year: number): { target: number | boolean | null; actual: number | null } {
  const target = task.yearTargets?.find((item) => item.year === year)?.target ?? null
  return { target, actual: task.displayYear === year ? (task.actualValue ?? null) : null }
}

const currentYear = new Date().getFullYear()
const totalRate = computed(() => trend.value?.cumulativeRate ?? null)
const currentRate = computed(() => trend.value?.years.find((item) => item.year === currentYear)?.completionRate ?? null)
const cumulativeActual = computed(() => trend.value?.cumulativeActual ?? null)

watch(indicatorId, () => {
  indicator.value = null
  plan.value = null
  tasks.value = []
  trend.value = null
  milestones.value = []
  if (indicatorId.value) void loadAll()
}, { immediate: true })
</script>

<template>
  <div class="page">
    <PageState :loading="loading && !indicator" :empty="!loading && !indicator" empty-text="指标不存在或无权查看">
      <template v-if="indicator">
        <div class="detail-head">
          <div class="row wrap">
            <StatusTag kind="IndicatorLevel" :value="indicator.level" />
            <span class="code">{{ indicator.code }}</span>
            <StatusTag kind="IndicatorNature" :value="indicator.nature" />
            <StatusTag v-if="indicator.statCaliber" kind="StatCaliber" :value="indicator.statCaliber" />
          </div>
          <h1>{{ indicator.name }}</h1>
          <p class="muted small">{{ plan?.name ?? '' }}</p>
          <div v-if="primaryAction" class="head-actions">
            <van-button
              size="small"
              :type="primaryAction === 'edit' ? 'primary' : 'warning'"
              @click="runPrimaryAction"
            >{{ primaryAction === 'edit' ? '编辑指标' : '发起调整' }}</van-button>
          </div>
        </div>

        <div v-if="indicator.status === 'DISTRIBUTED' && !isArchived" class="info-box">
          分工已下发，当前为只读视图；如需变更请发起调整
        </div>

        <div v-if="quantitative" class="card">
          <div class="card-title">
            <span>整体进度</span>
            <span class="small muted">{{ formatPercent(totalRate) }}</span>
          </div>
          <ProgressBar :rate="totalRate" :height="10" show-text />
          <div class="summary-grid">
            <div><span>五年目标</span><strong>{{ formatTargetValue(indicator.totalTarget, indicator.statCaliber) }}{{ indicator.unitLabel ? ` ${indicator.unitLabel}` : '' }}</strong></div>
            <div><span>累计完成</span><strong>{{ formatActualValue(cumulativeActual, indicator.statCaliber) }}</strong></div>
            <div><span>今年完成率</span><strong>{{ formatPercent(currentRate) }}</strong></div>
            <div><span>基线值</span><strong>{{ indicator.baselineValue ?? '—' }}</strong></div>
          </div>
        </div>

        <div class="card">
          <div class="card-title"><span>基础信息</span></div>
          <div class="kv-list">
            <div class="kv"><span class="kv__label">指标性质</span><span class="kv__value">{{ indicator.nature === 'CONSTRAINT' ? '约束性' : '发展性' }}</span></div>
            <div class="kv"><span class="kv__label">主要建设方向</span><span class="kv__value">{{ indicator.directionName ?? '—' }}</span></div>
            <div class="kv"><span class="kv__label">项目负责人</span><span class="kv__value">{{ indicator.owner?.name ?? '—' }}</span></div>
            <div class="kv"><span class="kv__label">是否可量化</span><span class="kv__value">{{ indicator.isQuantifiable ? '是' : '否' }}</span></div>
            <div class="kv"><span class="kv__label">计量类型</span><span class="kv__value">{{ indicator.measureType ?? '—' }}</span></div>
            <div class="kv"><span class="kv__label">汇总方式</span><span class="kv__value">{{ indicator.rollupMode === 'MANUAL' ? '手工认定' : '自动汇总' }}</span></div>
            <div class="kv"><span class="kv__label">达成方向</span><span class="kv__value">{{ indicator.targetDirection === 'LOWER_BETTER' ? '越小越好' : '越大越好' }}</span></div>
            <div class="kv"><span class="kv__label">最少材料数</span><span class="kv__value">{{ indicator.minFileCount ?? '—' }}</span></div>
            <div class="kv"><span class="kv__label">更新于</span><span class="kv__value">{{ formatDateTime(indicator.updatedAt) }}</span></div>
          </div>
          <div v-if="indicator.description" class="block-text">
            <div class="small muted">填写说明</div>
            <p>{{ indicator.description }}</p>
          </div>
          <div v-if="indicator.evidenceRequirement" class="block-text">
            <div class="small muted">材料考核要求</div>
            <p>{{ indicator.evidenceRequirement }}</p>
          </div>
        </div>

        <div v-if="quantitative" class="card">
          <div class="card-title"><span>年度进度</span></div>
          <div v-if="trend" class="trend-list">
            <div v-for="year in trend.years" :key="year.year" class="trend-item">
              <div class="row--between">
                <strong>{{ year.year }} 年</strong>
                <span class="small muted">目标 {{ formatTargetValue(year.target, trend.statCaliber) }}</span>
              </div>
              <div class="small">完成 {{ formatActualValue(year.actualValue, trend.statCaliber) }}</div>
              <ProgressBar :rate="year.completionRate" :height="6" show-text />
            </div>
          </div>
          <div v-else class="empty-state">暂无进度数据</div>
        </div>

        <div v-if="gantt && gantt.nodes.length > 0" class="card">
          <div class="card-title">
            <span>任务甘特视图</span>
            <span class="small muted">点击任务查看详情</span>
          </div>
          <div class="gantt-scroll">
            <div class="gantt-table" :style="{ '--year-count': gantt.years.length }">
              <div class="gantt-row gantt-head">
                <span class="gantt-name">任务</span>
                <span v-for="year in gantt.years" :key="year" class="gantt-year">{{ year }}</span>
              </div>
              <div
                v-for="node in gantt.nodes"
                :key="`${node.kind}-${node.id}`"
                class="gantt-row"
                :class="{ 'is-task': node.kind === 'task' }"
                @click="openGanttNode(node)"
              >
                <span class="gantt-name">
                  <span class="code">{{ node.code }}</span>
                  <span class="ellipsis">{{ node.name }}</span>
                </span>
                <span
                  v-for="year in gantt.years"
                  :key="year"
                  class="gantt-cell"
                  :class="`tone-${ganttYearCell(node, year).tone}`"
                >{{ ganttYearCell(node, year).label }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="quantitative" class="card">
          <div class="card-title">
            <span>学院任务</span>
            <span class="small muted">{{ tasks.length }} 条</span>
          </div>
          <div v-if="taskLoading" class="skeleton-block">加载任务…</div>
          <div v-else-if="tasks.length === 0" class="empty-state">该指标尚未分解学院任务</div>
          <div v-else class="task-list">
            <button
              v-for="task in tasks"
              :key="task.id"
              type="button"
              class="task-card"
              @click="openTask(task)"
            >
              <div class="row--between">
                <strong>{{ task.code }}</strong>
                <StatusTag kind="TaskStatus" :value="task.status" />
              </div>
              <div class="small muted">{{ task.unit?.name ?? '—' }} · {{ task.owner?.name ?? '未指定负责人' }}</div>
              <div class="task-years scroll-x">
                <div v-for="year in years" :key="year" class="task-year-cell">
                  <span>{{ year }}</span>
                  <strong>{{ formatTargetValue(yearTaskRow(task, year).target, primaryTaskIndicator(task)?.statCaliber) }}</strong>
                </div>
              </div>
            </button>
          </div>
        </div>

        <div class="card">
          <div class="card-title">
            <span>里程碑</span>
            <span class="small muted">{{ milestones.length }} 个</span>
          </div>
          <div v-if="milestones.length === 0" class="empty-state">该指标暂无里程碑</div>
          <div v-else class="milestone-list">
            <div v-for="item in milestones" :key="item.id" class="milestone-item">
              <div class="row--between">
                <strong>{{ item.name }}</strong>
                <StatusTag kind="MilestoneStatus" :value="item.status" />
              </div>
              <div class="small muted">{{ item.dueDate ?? '—' }}<template v-if="item.year"> · {{ item.year }} 年目标</template></div>
              <div v-if="item.deliverable" class="small">交付物：{{ item.deliverable }}</div>
            </div>
          </div>
        </div>
      </template>
    </PageState>

    <IndicatorFormSheet
      v-model="formVisible"
      :plan-id="indicator?.planId ?? ''"
      :indicator="indicator"
      :years="years"
      :direction-options="directions"
      :plan-archived="isArchived"
      @saved="loadAll"
    />
    <TaskDetailSheet v-model="taskSheetVisible" :task-id="taskSheetId" />
  </div>
</template>

<style scoped>
.detail-head {
  margin-bottom: 12px;
}
.detail-head h1 {
  margin: 8px 0 4px;
  font-size: 20px;
  line-height: 27px;
}
.detail-head p {
  margin: 0;
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
}
.head-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.info-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #eef3fc;
  color: #4a5f85;
  font-size: 12px;
}
.summary-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 10px;
}
.summary-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafd;
}
.summary-grid span {
  color: var(--text-secondary);
  font-size: 11px;
}
.summary-grid strong {
  font-size: 14px;
}
.block-text {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}
.block-text p {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 20px;
  white-space: pre-wrap;
}
.trend-list,
.task-list,
.milestone-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.trend-item {
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafd;
}
.task-card {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  color: var(--text);
  text-align: left;
}
.task-years {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  padding-bottom: 2px;
}
.task-year-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 58px;
  padding: 6px;
  border-radius: 6px;
  background: #fff;
  font-size: 11px;
}
.task-year-cell span {
  color: var(--text-secondary);
}
.milestone-item {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.gantt-scroll {
  overflow-x: auto;
  padding-bottom: 4px;
}
.gantt-table {
  min-width: 430px;
}
.gantt-row {
  display: grid;
  grid-template-columns: 136px repeat(var(--year-count), minmax(52px, 1fr));
  gap: 3px;
  align-items: center;
  padding: 4px 0;
  border-bottom: 1px solid var(--line);
  font-size: 10px;
}
.gantt-row:last-child {
  border-bottom: none;
}
.gantt-row.is-task {
  cursor: pointer;
}
.gantt-head {
  color: var(--text-secondary);
  font-weight: 600;
}
.gantt-name {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  padding-right: 4px;
  font-size: 11px;
}
.gantt-name .code {
  flex-shrink: 0;
  font-size: 10px;
}
.gantt-year {
  text-align: center;
}
.gantt-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 24px;
  border-radius: 4px;
  color: var(--text-secondary);
  font-size: 9px;
  white-space: nowrap;
}
.gantt-cell.tone-planned {
  background: #eef3fc;
  color: #4a5f85;
}
.gantt-cell.tone-partial {
  background: #fdf3e5;
  color: #8f5d16;
}
.gantt-cell.tone-complete {
  background: #e8f7f0;
  color: #147a51;
}
.gantt-cell.tone-none {
  background: #fdecec;
  color: #a33b3b;
}
</style>
