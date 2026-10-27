<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import ProgressWithTimeLine from '@/components/ProgressWithTimeLine.vue'
import IndicatorEditDrawer from './components/IndicatorEditDrawer.vue'
import { getIndicator, updateYearTargets } from '@/api/indicator'
import { getPlan } from '@/api/plan'
import { listTasks } from '@/api/task'
import { listMilestones } from '@/api/milestone'
import { listTaskAdjustments } from '@/api/adjustment'
import { listAuditLogs } from '@/api/audit'
import { getIndicatorTrend, getStatsOverview } from '@/api/stats'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { enumLabel } from '@/utils/enumLabels'
import {
  booleanPlanYear,
  formatTargetValue,
  hasQuantitativeFields,
  isPercentCaliber,
  sumYearTargets,
} from '@/utils/indicator'
import { EMPTY_DISPLAY, formatDate, formatDateTime, formatNumber, formatPercent } from '@/utils/format'
import { CHART_ACTUAL_COLOR, CHART_TARGET_COLOR, useEChart } from '@/utils/chart'
import { summarizeItems } from '@/utils/adjustment'
import type { IndicatorDetail, YearTarget } from '@/types/indicator'
import type { PlanDetail } from '@/types/plan'
import type { IndicatorTrend } from '@/types/stats'
import type { TaskListItem } from '@/types/task'
import type { Milestone } from '@/types/milestone'
import type { MilestoneStatus } from '@/types/enums'
import type { AdjustmentListItem } from '@/types/adjustment'
import type { AuditLogItem } from '@/types/audit'
import type { PageResult } from '@/types/api'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const indicatorId = computed(() => route.params.id as string)
const currentYear = new Date().getFullYear()

const loading = ref(false)
const indicator = ref<IndicatorDetail | null>(null)
const plan = ref<PlanDetail | null>(null)

const activeTab = ref('info')
const quantitative = computed(() => {
  const detail = indicator.value
  return !!detail && hasQuantitativeFields(detail.level, detail.isQuantifiable)
})

const canEdit = computed(() => userStore.can('indicator:edit') && (indicator.value?.editable ?? true))
const editable = computed(
  () => canEdit.value && !(plan.value?.indicatorsFinalized ?? false) && plan.value?.status !== 'ARCHIVED',
)

/** 只有 A+ 可进入分解矩阵 */
const canAllocate = computed(() => {
  const detail = indicator.value
  return !!detail && detail.level === 'A_PLUS' && userStore.can('allocation:edit')
})

const canApplyAdjustment = computed(() => quantitative.value && userStore.can('adjustment:apply'))

const years = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) {
    list.push(year)
  }
  return list
})

const parentOptions = computed(() => {
  const current = indicator.value
  if (!current || current.level !== 'A_PLUS') return []
  return current.parent ? [{ id: current.parent.id, code: current.parent.code, name: current.parent.name }] : []
})

// ---------- KPI（指标详情 + trend 接口，数值口径以后端返回为准） ----------
const trend = ref<IndicatorTrend | null>(null)
/** 当年时间进度（stats overview?year=，小数） */
const timeProgress = ref<number | null>(null)

/** 累计完成：后端五年累计口径（INCREMENT 各年之和；STOCK/GROWTH 最新总量；比率类最新值） */
const totalActual = computed<number | null>(() => trend.value?.cumulativeActual ?? null)

/** 五年完成率：后端累计口径（累计完成 ÷ 五年总量目标）；BOOLEAN 不适用 */
const fiveYearRate = computed<number | null>(() => {
  const detail = indicator.value
  if (!detail || detail.statCaliber === 'BOOLEAN') return null
  return trend.value?.cumulativeRate ?? null
})

/** 当年年度完成率（trend 对应年份的后端口径值） */
const yearRate = computed<number | null>(() => {
  const hit = trend.value?.years.find((item) => item.year === currentYear)
  return hit?.completionRate ?? null
})

const kpiTargetText = computed(() => {
  const detail = indicator.value
  if (!detail) return EMPTY_DISPLAY
  if (detail.statCaliber === 'BOOLEAN') {
    const planYear = booleanPlanYear(detail.yearTargets)
    return planYear === null ? EMPTY_DISPLAY : `是（${planYear} 年）`
  }
  return formatTargetValue(trend.value?.totalTarget ?? detail.totalTarget, detail.statCaliber)
})

const kpiActualText = computed(() => {
  const detail = indicator.value
  if (!detail) return EMPTY_DISPLAY
  if (detail.statCaliber === 'BOOLEAN') {
    const hit = [...(trend.value?.years ?? [])].reverse().find((item) => item.achieveStatus)
    return hit?.achieveStatus ? enumLabel('AchieveStatus', hit.achieveStatus, userStore.enums) : EMPTY_DISPLAY
  }
  return formatTargetValue(totalActual.value, detail.statCaliber)
})

// ---------- 目标 vs 实际柱状图 ----------
const targetChart = useEChart()

function renderTrendChart(): void {
  const t = trend.value
  const detail = indicator.value
  if (!t || !detail || detail.statCaliber === 'BOOLEAN') return
  const percent = isPercentCaliber(detail.statCaliber)
  const points = t.years
  targetChart.setOption({
    legend: { top: 0 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) =>
        typeof value === 'number' ? (percent ? formatPercent(value) : formatNumber(value)) : EMPTY_DISPLAY,
    },
    grid: { left: 8, right: 24, top: 32, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: points.map((p) => `${p.year}`) },
    yAxis: {
      type: 'value',
      axisLabel: percent ? { formatter: (value: number) => `${value * 100}%` } : undefined,
    },
    series: [
      {
        name: '目标',
        type: 'bar',
        barWidth: 24,
        itemStyle: { color: CHART_TARGET_COLOR },
        data: points.map((p) => (typeof p.target === 'number' ? p.target : null)),
      },
      {
        name: '实际',
        type: 'bar',
        barWidth: 24,
        itemStyle: { color: CHART_ACTUAL_COLOR },
        data: points.map((p) => p.actualValue),
      },
    ],
  })
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    const detail = await getIndicator(indicatorId.value)
    indicator.value = detail
    trend.value = null
    timeProgress.value = null
    if (!quantitative.value) activeTab.value = 'info'
    const [planDetail, trendData] = await Promise.all([
      getPlan(detail.planId),
      quantitative.value
        ? getIndicatorTrend(indicatorId.value).catch(() => null)
        : Promise.resolve(null),
    ])
    plan.value = planDetail
    trend.value = trendData
    if (quantitative.value) {
      initTargetForm()
      await nextTick()
      renderTrendChart()
      getStatsOverview(detail.planId, currentYear)
        .then((overview) => {
          timeProgress.value = overview.timeProgress
        })
        .catch(() => undefined)
    }
  } finally {
    loading.value = false
  }
}

// ---------- 编辑抽屉 ----------
const drawerVisible = ref(false)

function openEdit(): void {
  drawerVisible.value = true
}

function goAllocate(): void {
  const detail = indicator.value
  if (!detail || !canAllocate.value) return
  router.push({ path: `/plans/${detail.planId}/allocation`, query: { indicatorId: indicatorId.value } })
}

function goApplyAdjustment(): void {
  if (!canApplyAdjustment.value) return
  router.push({
    path: '/adjustments/apply',
    query: { indicatorId: indicatorId.value, ...(bTasks.value[0] ? { taskId: bTasks.value[0].id } : {}) },
  })
}

// ---------- 下属 B 任务（indicatorId 同时匹配任务的 indicatorId / topIndicatorId） ----------
const tasksLoading = ref(false)
const tasksLoaded = ref(false)
const bTasks = ref<TaskListItem[]>([])

async function loadBTasks(): Promise<void> {
  if (!quantitative.value) return
  tasksLoading.value = true
  try {
    const result = await listTasks({ indicatorId: indicatorId.value, page: 1, pageSize: 100 })
    bTasks.value = result.items
    tasksLoaded.value = true
  } finally {
    tasksLoading.value = false
  }
}

function taskCaliber(row: TaskListItem) {
  return row.indicator?.statCaliber ?? indicator.value?.statCaliber ?? 'INCREMENT'
}

// ---------- 里程碑 ----------
const milestoneLoading = ref(false)
const milestonesLoaded = ref(false)
const milestones = ref<Milestone[]>([])

async function loadMilestones(): Promise<void> {
  if (!quantitative.value) return
  milestoneLoading.value = true
  try {
    milestones.value = await listMilestones({ indicatorId: indicatorId.value })
    milestonesLoaded.value = true
  } finally {
    milestoneLoading.value = false
  }
}

/** 里程碑节点颜色（UI 规范：未开始灰 / 进行中蓝 / 已完成绿 / 逾期红 / 已延期紫） */
function milestoneNodeType(status: MilestoneStatus): 'primary' | 'success' | 'danger' | 'info' {
  switch (status) {
    case 'IN_PROGRESS':
      return 'primary'
    case 'COMPLETED':
      return 'success'
    case 'OVERDUE':
      return 'danger'
    default:
      return 'info'
  }
}

function milestoneNodeColor(status: MilestoneStatus): string | undefined {
  return status === 'POSTPONED' ? '#8E5CD9' : undefined
}

// ---------- 调整历史（合并该指标下全部 B 任务的调整记录） ----------
const adjustmentsLoading = ref(false)
const adjustmentsLoaded = ref(false)
const adjustments = ref<AdjustmentListItem[]>([])

async function loadAdjustments(): Promise<void> {
  if (!quantitative.value) return
  adjustmentsLoading.value = true
  try {
    if (!tasksLoaded.value) await loadBTasks()
    const groups = await Promise.all(
      bTasks.value.map((task) => listTaskAdjustments(task.id).catch(() => [] as AdjustmentListItem[])),
    )
    adjustments.value = groups
      .flat()
      .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
    adjustmentsLoaded.value = true
  } finally {
    adjustmentsLoading.value = false
  }
}

// ---------- 操作日志 ----------
const auditLoading = ref(false)
const auditLoaded = ref(false)
const auditDenied = ref(false)
const auditPage = ref<PageResult<AuditLogItem>>({ items: [], total: 0, page: 1, pageSize: 10 })

async function loadAuditLogs(page = 1): Promise<void> {
  auditLoading.value = true
  try {
    auditPage.value = await listAuditLogs({
      resourceType: 'indicator',
      resourceId: indicatorId.value,
      page,
      pageSize: auditPage.value.pageSize,
    })
    auditLoaded.value = true
  } catch {
    auditDenied.value = true
  } finally {
    auditLoading.value = false
  }
}

function auditSummary(row: AuditLogItem): string {
  return row.reason ?? row.resourceCode ?? EMPTY_DISPLAY
}

// ---------- 年度目标编辑 ----------
interface TargetFormState {
  totalTarget: number | null
  /** key: year；数值 / 布尔目标 */
  targets: Record<number, number | boolean | null>
  /** 比率类按 % 显示 */
  percentTargets: Record<number, number | null>
  planYear: number | null
}

const targetForm = reactive<TargetFormState>({
  totalTarget: null,
  targets: {},
  percentTargets: {},
  planYear: null,
})

function initTargetForm(): void {
  const detail = indicator.value
  if (!detail || !quantitative.value) return
  targetForm.totalTarget = detail.totalTarget
  targetForm.planYear = booleanPlanYear(detail.yearTargets)
  for (const year of years.value) {
    const hit = detail.yearTargets.find((item) => item.year === year)
    const value = hit?.target ?? null
    if (isPercentCaliber(detail.statCaliber)) {
      targetForm.percentTargets[year] = typeof value === 'number' ? value * 100 : null
    } else {
      targetForm.targets[year] = value
    }
  }
}

const yearSum = computed(() => {
  const list: YearTarget[] = years.value.map((year) => ({
    year,
    target: targetForm.targets[year] ?? null,
  }))
  return sumYearTargets(list)
})

const sumDiff = computed(() => {
  if (targetForm.totalTarget === null) return null
  return yearSum.value - targetForm.totalTarget
})

const savingTargets = ref(false)

function buildYearTargets(): YearTarget[] {
  const detail = indicator.value!
  if (detail.statCaliber === 'BOOLEAN') {
    return years.value.map((year) => ({ year, target: year === targetForm.planYear ? true : null }))
  }
  if (isPercentCaliber(detail.statCaliber)) {
    return years.value.map((year) => ({
      year,
      target:
        targetForm.percentTargets[year] === null || targetForm.percentTargets[year] === undefined
          ? null
          : Number((targetForm.percentTargets[year]! / 100).toFixed(6)),
    }))
  }
  return years.value.map((year) => ({ year, target: targetForm.targets[year] ?? null }))
}

async function saveYearTargets(): Promise<void> {
  const detail = indicator.value
  if (!detail || !quantitative.value || !editable.value) return
  savingTargets.value = true
  try {
    const updated = await updateYearTargets(detail.id, {
      totalTarget:
        detail.statCaliber === 'BOOLEAN' || isPercentCaliber(detail.statCaliber)
          ? null
          : targetForm.totalTarget,
      yearTargets: buildYearTargets(),
      version: detail.version,
    })
    indicator.value = { ...updated, parent: detail.parent, children: detail.children, editable: detail.editable }
    ElMessage.success('年度目标已保存')
  } catch (error) {
    if (error instanceof ApiError && (error.code === 40002 || error.code === 40005)) {
      const details = error.fieldErrors.map((item) => `${item.field}：${item.message}`)
      ElMessageBox.alert(
        details.length > 0 ? details.join('\n') : error.message,
        `保存失败（${error.code}）`,
        { confirmButtonText: '我知道了', type: 'error' },
      )
    }
  } finally {
    savingTargets.value = false
  }
}

function goChild(childId: string): void {
  router.push(`/indicators/${childId}`)
}

watch(
  () => route.query.tab,
  (tab) => {
    if (typeof tab === 'string' && tab) activeTab.value = tab
  },
  { immediate: true },
)

watch(activeTab, (tab) => {
  if (tab === 'tasks' && !tasksLoaded.value && !tasksLoading.value) loadBTasks()
  if (tab === 'milestones' && !milestonesLoaded.value && !milestoneLoading.value) loadMilestones()
  if (tab === 'adjustments' && !adjustmentsLoaded.value && !adjustmentsLoading.value) loadAdjustments()
  if (tab === 'logs' && !auditLoaded.value && !auditLoading.value && !auditDenied.value) loadAuditLogs(1)
  if (tab === 'targets') nextTick().then(renderTrendChart)
})

// 切换指标（A → A+ 下钻时组件复用，onMounted 不再触发）：重置懒加载标记并整页重载
watch(indicatorId, async (id) => {
  if (!id) return
  tasksLoaded.value = false
  milestonesLoaded.value = false
  adjustmentsLoaded.value = false
  auditLoaded.value = false
  await loadAll()
  // 停在懒加载 Tab 时补载（标记已重置）
  if (activeTab.value === 'tasks') loadBTasks()
  else if (activeTab.value === 'milestones') loadMilestones()
  else if (activeTab.value === 'adjustments') loadAdjustments()
  else if (activeTab.value === 'logs' && !auditDenied.value) loadAuditLogs(1)
}, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <template v-if="indicator">
      <div class="page-header">
        <div>
          <h1 class="page-title">
            <StatusTag kind="IndicatorLevel" :value="indicator.level" />
            <span class="mono">{{ indicator.code }}</span>
            {{ indicator.name }}
            <StatusTag kind="IndicatorNature" :value="indicator.nature" />
            <StatusTag v-if="indicator.statCaliber" kind="StatCaliber" :value="indicator.statCaliber" />
          </h1>
          <p class="page-subtitle">
            <template v-if="indicator.parent">
              上级：{{ indicator.parent.code }} {{ indicator.parent.name }} ·
            </template>
            {{ plan?.name ?? '' }}
          </p>
        </div>
        <div class="header-actions">
          <el-button v-if="canAllocate" @click="goAllocate">分解</el-button>
          <el-button v-if="canApplyAdjustment" type="warning" plain @click="goApplyAdjustment">
            发起调整
          </el-button>
          <el-button v-if="canEdit" type="primary" @click="openEdit">编辑</el-button>
        </div>
      </div>

      <div v-if="quantitative" class="kpi-row">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">{{ kpiTargetText }}</div>
          <div class="kpi-label">五年目标{{ indicator.unitLabel ? `（${indicator.unitLabel}）` : '' }}</div>
        </el-card>
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">{{ kpiActualText }}</div>
          <div class="kpi-label">累计完成（审核通过）</div>
        </el-card>
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">{{ formatPercent(fiveYearRate) }}</div>
          <div class="kpi-label">五年完成率</div>
        </el-card>
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">{{ formatPercent(yearRate) }}</div>
          <div class="kpi-label">
            {{ currentYear }} 年度完成率
            <span v-if="timeProgress !== null" class="kpi-sub">vs 时间进度 {{ formatPercent(timeProgress) }}</span>
          </div>
        </el-card>
      </div>

      <el-alert
        v-if="quantitative && !editable && canEdit"
        type="info"
        :closable="false"
        title="指标体系已定稿或计划已归档，年度目标只读；修改目标需发起目标调整（40005）"
        class="page-alert"
      />

      <el-tabs v-model="activeTab">
        <el-tab-pane label="基本信息" name="info">
          <el-card shadow="never">
            <el-descriptions :column="3" border>
              <el-descriptions-item label="负责人">{{ indicator.owner?.name ?? EMPTY_DISPLAY }}</el-descriptions-item>
              <el-descriptions-item v-if="indicator.level === 'A'" label="是否可量化">
                {{ indicator.isQuantifiable ? '是' : '否' }}
              </el-descriptions-item>
              <template v-if="quantitative">
                <el-descriptions-item label="计量类型">
                  {{ indicator.measureType ? enumLabel('MeasureType', indicator.measureType, userStore.enums) : EMPTY_DISPLAY }}
                </el-descriptions-item>
                <el-descriptions-item label="统计口径">
                  {{ indicator.statCaliber ? enumLabel('StatCaliber', indicator.statCaliber, userStore.enums) : EMPTY_DISPLAY }}
                </el-descriptions-item>
                <el-descriptions-item label="计量单位">{{ indicator.unitLabel ?? EMPTY_DISPLAY }}</el-descriptions-item>
                <el-descriptions-item label="达成方向">
                  {{ indicator.targetDirection ? enumLabel('TargetDirection', indicator.targetDirection, userStore.enums) : EMPTY_DISPLAY }}
                </el-descriptions-item>
                <el-descriptions-item label="汇总方式">
                  {{ indicator.rollupMode ? enumLabel('RollupMode', indicator.rollupMode, userStore.enums) : EMPTY_DISPLAY }}
                </el-descriptions-item>
                <el-descriptions-item label="基线值">{{ formatNumber(indicator.baselineValue) }}</el-descriptions-item>
                <el-descriptions-item label="五年目标">
                  {{ indicator.statCaliber === 'BOOLEAN'
                    ? (booleanPlanYear(indicator.yearTargets) === null ? EMPTY_DISPLAY : `是（${booleanPlanYear(indicator.yearTargets)} 年）`)
                    : formatTargetValue(indicator.totalTarget, indicator.statCaliber) }}
                </el-descriptions-item>
              </template>
              <el-descriptions-item label="状态">
                <StatusTag kind="IndicatorStatus" :value="indicator.status" />
              </el-descriptions-item>
              <el-descriptions-item label="填写说明" :span="3">
                {{ indicator.description ?? EMPTY_DISPLAY }}
              </el-descriptions-item>
              <el-descriptions-item v-if="quantitative" label="佐证材料要求" :span="3">
                {{ indicator.evidenceRequirement ?? EMPTY_DISPLAY }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-tab-pane>

        <el-tab-pane v-if="quantitative" label="年度目标与完成" name="targets">
          <el-card shadow="never">
            <el-form label-width="110px" class="target-form">
              <el-form-item
                v-if="indicator.statCaliber !== 'BOOLEAN' && !isPercentCaliber(indicator.statCaliber)"
                label="五年总量"
              >
                <el-input-number
                  v-model="targetForm.totalTarget"
                  :min="0"
                  :precision="4"
                  :controls-position="'right'"
                  :disabled="!editable"
                />
                <span v-if="indicator.unitLabel" class="unit-text">{{ indicator.unitLabel }}</span>
              </el-form-item>

              <el-form-item v-if="indicator.statCaliber === 'BOOLEAN'" label="计划完成年">
                <el-radio-group v-model="targetForm.planYear" :disabled="!editable">
                  <el-radio v-for="year in years" :key="year" :value="year">{{ year }} 年</el-radio>
                </el-radio-group>
              </el-form-item>

              <el-form-item v-else label="各年度目标">
                <div class="year-grid">
                  <div v-for="year in years" :key="year" class="year-cell">
                    <div class="year-label">{{ year }}</div>
                    <template v-if="isPercentCaliber(indicator.statCaliber)">
                      <el-input-number
                        v-model="targetForm.percentTargets[year]"
                        :min="0"
                        :precision="1"
                        :controls-position="'right'"
                        :disabled="!editable"
                      />
                      <span class="unit-text">%</span>
                    </template>
                    <el-input-number
                      v-else
                      v-model="targetForm.targets[year]"
                      :min="0"
                      :precision="4"
                      :controls-position="'right'"
                      :disabled="!editable"
                    />
                  </div>
                </div>
              </el-form-item>

              <el-form-item v-if="indicator.statCaliber === 'INCREMENT'" label="守恒校验">
                <span
                  class="sum-check"
                  :class="sumDiff === 0 ? 'sum-ok' : 'sum-error'"
                >
                  各年度之和 {{ formatNumber(yearSum) }}，五年总量
                  {{ formatNumber(targetForm.totalTarget) }}
                  <template v-if="sumDiff !== null">
                    （{{ sumDiff === 0 ? '0 ✓' : `差值 ${sumDiff > 0 ? '+' : ''}${sumDiff}` }}）
                  </template>
                </span>
                <div class="field-tip">增量累计型要求各年度目标之和 = 五年总量（40002）</div>
              </el-form-item>

              <el-form-item v-if="editable">
                <el-button type="primary" :loading="savingTargets" @click="saveYearTargets">
                  保存年度目标
                </el-button>
              </el-form-item>
            </el-form>

            <template v-if="indicator.statCaliber !== 'BOOLEAN'">
              <el-divider />
              <h3 class="section-title">各年度目标 vs 实际完成</h3>
              <div v-if="trend && trend.years.length > 0" :ref="targetChart.container" class="trend-chart" />
              <el-empty v-else :image-size="80" description="暂无趋势数据" />
            </template>
          </el-card>
        </el-tab-pane>

        <el-tab-pane v-if="quantitative && indicator.level === 'A'" :label="`下属 A+（${indicator.children.length}）`" name="children">
          <el-card shadow="never">
            <el-table :data="indicator.children" border size="small">
              <el-table-column prop="code" label="编号" width="110" class-name="mono" />
              <el-table-column prop="name" label="名称" min-width="220" show-overflow-tooltip />
              <el-table-column label="口径" width="80" align="center">
                <template #default="{ row }">
                  <StatusTag v-if="row.statCaliber" kind="StatCaliber" :value="row.statCaliber" />
                  <span v-else>{{ EMPTY_DISPLAY }}</span>
                </template>
              </el-table-column>
              <el-table-column label="五年目标" width="110" align="right">
                <template #default="{ row }">
                  {{ formatTargetValue(row.totalTarget, row.statCaliber) }}
                </template>
              </el-table-column>
              <el-table-column label="负责人" width="90" align="center">
                <template #default="{ row }">{{ row.owner?.name ?? EMPTY_DISPLAY }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90" align="center">
                <template #default="{ row }">
                  <StatusTag kind="IndicatorStatus" :value="row.status" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" @click="goChild(row.id)">详情</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty :image-size="80" description="尚未拆出 A+ 指标" />
              </template>
            </el-table>
          </el-card>
        </el-tab-pane>

        <el-tab-pane v-if="quantitative" label="下属 B 任务" name="tasks">
          <el-card shadow="never">
            <el-table v-loading="tasksLoading" :data="bTasks" border size="small">
              <el-table-column label="任务编号" width="140" class-name="mono">
                <template #default="{ row }">
                  <div>{{ row.code }}</div>
                  <el-tag v-if="row.isHistorical" type="info" size="small">历史只读</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="执行单元" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.unit ? `${row.unit.code} ${row.unit.name}` : EMPTY_DISPLAY }}
                </template>
              </el-table-column>
              <el-table-column label="负责人" width="90" align="center">
                <template #default="{ row }">{{ row.owner?.name ?? EMPTY_DISPLAY }}</template>
              </el-table-column>
              <el-table-column label="五年目标" width="100" align="right">
                <template #default="{ row }">
                  {{ formatTargetValue(row.totalTarget ?? null, taskCaliber(row)) }}
                </template>
              </el-table-column>
              <el-table-column label="本年目标" width="100" align="right">
                <template #default="{ row }">
                  {{ formatTargetValue(row.yearTarget ?? null, taskCaliber(row)) }}
                </template>
              </el-table-column>
              <el-table-column label="累计完成" width="100" align="right">
                <template #default="{ row }">
                  {{ formatTargetValue(row.cumulativeActual ?? null, taskCaliber(row)) }}
                </template>
              </el-table-column>
              <el-table-column label="五年累计进度" min-width="150">
                <template #default="{ row }">
                  <ProgressWithTimeLine :rate="row.cumulativeRate ?? null" />
                </template>
              </el-table-column>
              <el-table-column label="本年完成率" width="100" align="right">
                <template #default="{ row }">
                  {{ formatPercent(row.completionRate ?? null) }}
                </template>
              </el-table-column>
              <el-table-column label="年度状态" width="96" align="center">
                <template #default="{ row }">
                  <StatusTag v-if="row.yearStatus" kind="TaskYearStatus" :value="row.yearStatus" />
                  <span v-else class="empty-text">{{ EMPTY_DISPLAY }}</span>
                </template>
              </el-table-column>
              <el-table-column label="任务状态" width="96" align="center">
                <template #default="{ row }">
                  <StatusTag kind="TaskStatus" :value="row.status" />
                </template>
              </el-table-column>
              <template #empty>
                <el-empty
                  :image-size="80"
                  :description="indicator.level === 'A_PLUS'
                    ? '尚未下发 B 任务，可点击右上角「分解」配置分解矩阵'
                    : '暂无下属 B 任务'"
                />
              </template>
            </el-table>
          </el-card>
        </el-tab-pane>

        <el-tab-pane v-if="quantitative" label="里程碑" name="milestones">
          <el-card shadow="never" v-loading="milestoneLoading">
            <el-timeline v-if="milestones.length > 0" class="ms-timeline">
              <el-timeline-item
                v-for="item in milestones"
                :key="item.id"
                :timestamp="`${formatDate(item.dueDate)}${item.year ? ` · 关联 ${item.year} 年目标` : ''}`"
                placement="top"
                :type="milestoneNodeType(item.status)"
                :color="milestoneNodeColor(item.status)"
              >
                <div class="ms-head">
                  <span class="ms-name">{{ item.name }}</span>
                  <StatusTag kind="MilestoneStatus" :value="item.status" />
                </div>
                <div v-if="item.deliverable" class="ms-line">交付物：{{ item.deliverable }}</div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else-if="milestonesLoaded && !milestoneLoading" :image-size="80" description="暂无里程碑" />
          </el-card>
        </el-tab-pane>

        <el-tab-pane v-if="quantitative" label="调整历史" name="adjustments">
          <el-card shadow="never" v-loading="adjustmentsLoading">
            <el-timeline v-if="adjustments.length > 0" class="ms-timeline">
              <el-timeline-item
                v-for="item in adjustments"
                :key="item.id"
                :timestamp="formatDateTime(item.createdAt)"
                placement="top"
                :type="item.status === 'APPROVED' ? 'success' : item.status === 'REJECTED' || item.status === 'WITHDRAWN' ? 'info' : 'primary'"
              >
                <div class="ms-head">
                  <span class="ms-name">
                    {{ enumLabel('AdjustmentType', item.type, userStore.enums) }}
                    <span v-if="item.task" class="mono ms-task-code">{{ item.task.code }}</span>
                  </span>
                  <StatusTag kind="AdjustmentStatus" :value="item.status" />
                </div>
                <div class="ms-line">{{ summarizeItems(item.items ?? [], indicator.unitLabel) }}</div>
                <div v-if="item.fromUnit || item.toUnit" class="ms-line">
                  {{ item.fromUnit?.name ?? EMPTY_DISPLAY }} → {{ item.toUnit?.name ?? EMPTY_DISPLAY }}
                </div>
                <div class="ms-meta">
                  申请人：{{ item.applicant?.name ?? EMPTY_DISPLAY }}
                  <template v-if="item.reason"> · {{ item.reason }}</template>
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty
              v-else-if="adjustmentsLoaded && !adjustmentsLoading"
              :image-size="80"
              description="该指标暂无调整记录"
            />
          </el-card>
        </el-tab-pane>

        <el-tab-pane label="操作日志" name="logs">
          <el-card shadow="never" v-loading="auditLoading">
            <el-alert
              v-if="auditDenied"
              type="info"
              :closable="false"
              title="当前账号无操作日志查看权限（audit-log:view）"
            />
            <template v-else>
              <el-table :data="auditPage.items" border size="small">
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ formatDateTime(row.occurredAt) }}</template>
                </el-table-column>
                <el-table-column label="操作人" width="100" align="center">
                  <template #default="{ row }">{{ row.operator?.name ?? EMPTY_DISPLAY }}</template>
                </el-table-column>
                <el-table-column label="动作" width="110" align="center">
                  <template #default="{ row }">{{ enumLabel('AuditAction', row.action, userStore.enums) }}</template>
                </el-table-column>
                <el-table-column label="摘要" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">{{ auditSummary(row) }}</template>
                </el-table-column>
                <template #empty>
                  <el-empty :image-size="80" description="暂无操作日志" />
                </template>
              </el-table>
              <div class="pager">
                <el-pagination
                  :current-page="auditPage.page"
                  :page-size="auditPage.pageSize"
                  :total="auditPage.total"
                  layout="total, prev, pager, next"
                  @current-change="loadAuditLogs"
                />
              </div>
            </template>
          </el-card>
        </el-tab-pane>
      </el-tabs>

      <IndicatorEditDrawer
        v-model="drawerVisible"
        :plan-id="indicator.planId"
        :indicator="indicator"
        :preset-parent-id="null"
        :parents="parentOptions"
        :years="years"
        @saved="loadAll"
      />
    </template>
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
  display: flex;
  align-items: center;
  gap: var(--space-2);
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

.page-alert {
  margin-bottom: var(--space-4);
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.kpi-card {
  border-radius: var(--radius-card);
  text-align: center;
}

.kpi-value {
  font-size: 32px;
  font-weight: 600;
  line-height: 40px;
  font-variant-numeric: tabular-nums;
}

.kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: var(--space-1);
}

.kpi-sub {
  margin-left: var(--space-1);
  color: var(--el-color-warning);
}

.mono {
  font-family: var(--app-font-mono);
}

.target-form {
  max-width: 960px;
}

.year-grid {
  display: flex;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.year-cell {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.year-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.unit-text {
  margin-left: var(--space-2);
  color: var(--el-text-color-secondary);
}

.sum-check {
  font-weight: 600;
}

.sum-ok {
  color: var(--el-color-success);
}

.sum-error {
  color: var(--el-color-danger);
}

.field-tip {
  font-size: 12px;
  line-height: 20px;
  color: var(--el-text-color-secondary);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 var(--space-3);
}

.trend-chart {
  height: 320px;
}

.empty-text {
  color: var(--el-text-color-placeholder);
}

.ms-timeline {
  padding-left: var(--space-1);
}

.ms-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.ms-name {
  font-weight: 600;
  font-size: 13px;
}

.ms-task-code {
  margin-left: var(--space-2);
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.ms-line {
  margin-top: 2px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.ms-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
    gap: var(--space-2);
  }

  .kpi-value {
    font-size: 24px;
    line-height: 32px;
  }

  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }
}
</style>
