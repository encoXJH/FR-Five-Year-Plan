<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ProgressWithTimeLine from '@/components/ProgressWithTimeLine.vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import StatusTag from '@/components/StatusTag.vue'
import MilestonePanel from './components/MilestonePanel.vue'
import {
  activatePlan,
  activatePlanYear,
  archivePlan,
  getPlan,
  savePlanAsTemplate,
} from '@/api/plan'
import { getStatsByIndicator, getStatsByUnit, getStatsOverview } from '@/api/stats'
import { finalSettle, getYearSettlement, settleYear, unlockYear } from '@/api/settlement'
import { getTodos, listNotifications, markNotificationRead } from '@/api/todo'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { enumLabel } from '@/utils/enumLabels'
import { formatPercent, formatRelativeTime } from '@/utils/format'
import { progressPercentage } from '@/utils/task'
import { resolveTodoLink, todoGroupLabel } from '@/utils/todo'
import type { PlanDetail, PlanYear } from '@/types/plan'
import type {
  IndicatorStat,
  StatsOverview,
  UnitStatItem,
  WarningItem,
  YearSettlement,
} from '@/types/stats'
import type { TodoItem, TodosResult } from '@/types/todo'
import type { NotificationItem } from '@/types/notification'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const planId = computed(() => route.params.planId as string)
const loading = ref(false)
const plan = ref<PlanDetail | null>(null)

const canManage = computed(() => userStore.can('plan:manage'))
const canSettle = computed(() => userStore.can('settlement:manage'))
const isArchived = computed(() => plan.value?.status === 'ARCHIVED')

/** 计划年度列表（里程碑关联年度下拉） */
const planYears = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) {
    list.push(year)
  }
  return list
})

/** 当前激活年度（统计 KPI 的年度口径；无激活年度时为五年累计） */
const activeYear = computed<number | null>(
  () => plan.value?.years.find((y) => y.status === 'ACTIVE')?.year ?? null,
)

async function loadPlan(): Promise<void> {
  loading.value = true
  try {
    plan.value = await getPlan(planId.value)
  } finally {
    loading.value = false
  }
}

async function handleActivate(): Promise<void> {
  await ElMessageBox.confirm('启动后计划进入「执行中」，学院可按年度填报。确认启动？', '启动计划', {
    confirmButtonText: '确认启动',
    cancelButtonText: '取消',
  })
  if (!plan.value) return
  plan.value = await activatePlan(planId.value, plan.value.version)
  ElMessage.success('计划已启动')
}

const activatingYear = ref<number | null>(null)

async function handleActivateYear(year: PlanYear): Promise<void> {
  await ElMessageBox.confirm(
    `激活 ${year.year} 年度后，各学院即可填报该年度数据。确认激活？`,
    '激活年度',
    { confirmButtonText: '确认激活', cancelButtonText: '取消' },
  )
  if (!plan.value) return
  activatingYear.value = year.year
  try {
    plan.value = await activatePlanYear(planId.value, year.year, plan.value.version, newIdempotencyKey())
    ElMessage.success(`${year.year} 年度已激活`)
  } finally {
    activatingYear.value = null
  }
}

// ---------- 年度核算 / 解锁 / 五年核算（settlement:manage） ----------
const settlingYear = ref<number | null>(null)
const unlockingYear = ref<number | null>(null)
const finalSettling = ref(false)

async function handleSettleYear(year: PlanYear): Promise<void> {
  await ElMessageBox.confirm(
    `将锁定 ${year.year} 年度数据并生成核算结果：锁定后该年度的填报、审核、分解与调整全部只读，未达标任务将生成「待补做」记录。确认核算？`,
    `年度核算 · ${year.year}`,
    { confirmButtonText: '确认核算', cancelButtonText: '取消', type: 'warning' },
  )
  if (!plan.value) return
  settlingYear.value = year.year
  try {
    await settleYear(planId.value, year.year, plan.value.version, newIdempotencyKey())
    ElMessage.success(`${year.year} 年度核算完成`)
    await loadPlan()
    settlementYear.value = year.year
    await loadSettlement()
    await loadStats()
  } finally {
    settlingYear.value = null
  }
}

const unlockDialogVisible = ref(false)
const unlockTargetYear = ref<number | null>(null)

function openUnlockDialog(year: PlanYear): void {
  unlockTargetYear.value = year.year
  unlockDialogVisible.value = true
}

async function submitUnlock(reason: string): Promise<void> {
  if (!plan.value || unlockTargetYear.value == null) return
  unlockingYear.value = unlockTargetYear.value
  try {
    await unlockYear(
      planId.value,
      unlockTargetYear.value,
      reason,
      plan.value.version,
      newIdempotencyKey(),
    )
    ElMessage.success(`${unlockTargetYear.value} 年度已解锁`)
    unlockDialogVisible.value = false
    await loadPlan()
    await loadStats()
  } finally {
    unlockingYear.value = null
  }
}

/** 五年核算：规划期所有年度核算锁定后开放（PRD FR-M8-03） */
const allYearsLocked = computed(
  () =>
    (plan.value?.years.length ?? 0) > 0 && plan.value!.years.every((y) => y.status === 'LOCKED'),
)

async function handleFinalSettle(): Promise<void> {
  await ElMessageBox.confirm(
    `五年核算将汇总 ${plan.value?.startYear}–${plan.value?.endYear} 全部年度数据，生成五年目标达成结论。确认执行？`,
    '五年核算',
    { confirmButtonText: '确认核算', cancelButtonText: '取消', type: 'warning' },
  )
  if (!plan.value) return
  finalSettling.value = true
  try {
    const result = await finalSettle(planId.value, plan.value.version, newIdempotencyKey())
    ElMessageBox.alert(
      `五年核算完成：共 ${result.indicatorCount} 项指标，达标 ${result.achievedCount} 项，未达标 ${result.indicatorCount - result.achievedCount} 项。`,
      '五年核算完成',
      { confirmButtonText: '知道了' },
    )
    await loadPlan()
    await loadStats()
  } finally {
    finalSettling.value = false
  }
}

// ---------- 核算结果面板 ----------
const lockedYears = computed<number[]>(
  () => plan.value?.years.filter((y) => y.status === 'LOCKED').map((y) => y.year) ?? [],
)
const settlementYear = ref<number | null>(null)
const settlement = ref<YearSettlement | null>(null)
const settlementLoading = ref(false)

async function loadSettlement(): Promise<void> {
  if (settlementYear.value == null) {
    settlement.value = null
    return
  }
  settlementLoading.value = true
  try {
    settlement.value = await getYearSettlement(planId.value, settlementYear.value)
  } catch {
    settlement.value = null
  } finally {
    settlementLoading.value = false
  }
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

// ---------- 统计 KPI（stats 接口未就绪时保持占位，不影响主流程） ----------
const overview = ref<StatsOverview | null>(null)
const overviewAll = ref<StatsOverview | null>(null)
const byIndicator = ref<IndicatorStat[]>([])
const byUnit = ref<UnitStatItem[]>([])

/** 学院视角只显示本单元名次与完成率（UI 规范 P03 Q12） */
const isUnitView = computed(
  () => userStore.roles.includes('UNIT_OWNER') && !userStore.can('settlement:manage'),
)

const rankRows = computed(() => {
  const sorted = [...byUnit.value].sort((a, b) => (b.completionRate ?? -1) - (a.completionRate ?? -1))
  if (!isUnitView.value) return sorted
  const myUnitId = userStore.userInfo?.unit?.id
  return sorted.filter((item) => item.unit?.id === myUnitId)
})

async function loadStats(): Promise<void> {
  try {
    const y = activeYear.value ?? undefined
    const [oAll, o, i, u] = await Promise.all([
      getStatsOverview(planId.value),
      getStatsOverview(planId.value, y),
      getStatsByIndicator(planId.value, y),
      getStatsByUnit(planId.value, y),
    ])
    overviewAll.value = oAll
    overview.value = o
    byIndicator.value = i
    byUnit.value = u
  } catch {
    overviewAll.value = null
    overview.value = null
    byIndicator.value = []
    byUnit.value = []
  }
}

/** 预警指标（前 5 条）：滞后差值 ≥ 20 个百分点标红，否则橙色 */
const topWarnings = computed<WarningItem[]>(() => (overview.value?.warnings ?? []).slice(0, 5))

function warningLevel(item: WarningItem): 'danger' | 'warning' {
  return (item.lagGap ?? 0) >= 0.2 ? 'danger' : 'warning'
}

// ---------- 我的待办 / 最近动态（各前 5 条） ----------
const todosResult = ref<TodosResult | null>(null)
const topTodos = computed<TodoItem[]>(() => (todosResult.value?.items ?? []).slice(0, 5))
const reviewPendingCount = computed(() => Number(todosResult.value?.summary?.REVIEW ?? 0))
const approvalPendingCount = computed(() => Number(todosResult.value?.summary?.APPROVAL ?? 0))

async function loadTodos(): Promise<void> {
  try {
    todosResult.value = await getTodos()
  } catch {
    todosResult.value = null
  }
}

function goTodo(todo: TodoItem): void {
  const link = resolveTodoLink(todo)
  if (link) router.push(link)
  else ElMessage.info('该待办暂无可跳转页面')
}

const topNotifications = ref<NotificationItem[]>([])

async function loadNotifications(): Promise<void> {
  try {
    const result = await listNotifications({ page: 1, pageSize: 5 })
    topNotifications.value = result.items
  } catch {
    topNotifications.value = []
  }
}

async function openNotification(item: NotificationItem): Promise<void> {
  if (!item.readAt) {
    try {
      const updated = await markNotificationRead(item.id)
      item.readAt = updated.readAt
    } catch {
      // 拦截器已提示
    }
  }
  if (item.link) router.push(item.link)
}

async function handleArchive(): Promise<void> {
  await ElMessageBox.confirm(
    `归档后「${plan.value?.name}」只能查看，不能修改。确认归档？`,
    '归档计划',
    { confirmButtonText: '确认归档', cancelButtonText: '取消', type: 'warning' },
  )
  if (!plan.value) return
  plan.value = await archivePlan(planId.value, plan.value.version)
  ElMessage.success('计划已归档')
}

const templateName = ref('')
const templateDialogVisible = ref(false)
const savingTemplate = ref(false)

function openSaveAsTemplate(): void {
  templateName.value = `${plan.value?.name ?? ''} 模板`
  templateDialogVisible.value = true
}

async function submitSaveAsTemplate(): Promise<void> {
  if (!templateName.value.trim()) {
    ElMessage.warning('请填写模板名称')
    return
  }
  savingTemplate.value = true
  try {
    await savePlanAsTemplate(planId.value, { name: templateName.value.trim() })
    ElMessage.success('已另存为模板')
    templateDialogVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    savingTemplate.value = false
  }
}

function goIndicators(): void {
  router.push(`/plans/${planId.value}/indicators`)
}

// planId 驱动加载：顶栏切换计划时组件复用不触发 onMounted，用 watch 保证切换即刷新
watch(planId, async (id) => {
  if (!id) return
  await loadPlan()
  settlementYear.value = lockedYears.value[0] ?? null
  await Promise.all([loadStats(), loadSettlement(), loadTodos(), loadNotifications()])
}, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <template v-if="plan">
      <el-alert
        v-if="isArchived"
        type="info"
        :closable="false"
        title="该计划已归档，只能查看"
        class="page-alert"
      />

      <div class="page-header">
        <div>
          <h1 class="page-title">
            {{ plan.name }}
            <StatusTag kind="PlanStatus" :value="plan.status" class="title-tag" />
          </h1>
          <p class="page-subtitle">
            周期 {{ plan.startYear }}–{{ plan.endYear }} · 基线 {{ plan.baselineYear }}
            <template v-if="plan.settleDeadline"> · 核算截止 {{ plan.settleDeadline }}</template>
          </p>
        </div>
        <div class="header-actions">
          <el-button type="primary" @click="goIndicators">进入指标总表</el-button>
          <template v-if="canManage && !isArchived">
            <el-button v-if="plan.status === 'DRAFT'" type="primary" @click="handleActivate">
              启动执行
            </el-button>
            <el-button @click="openSaveAsTemplate">另存为模板</el-button>
            <el-button type="danger" plain @click="handleArchive">归档</el-button>
          </template>
        </div>
      </div>

      <el-card shadow="never" class="info-card">
        <el-descriptions :column="4" border>
          <el-descriptions-item label="计划周期">
            {{ plan.startYear }}–{{ plan.endYear }}（共 {{ plan.endYear - plan.startYear + 1 }} 年）
          </el-descriptions-item>
          <el-descriptions-item label="基线年">{{ plan.baselineYear }}</el-descriptions-item>
          <el-descriptions-item label="核算截止日">{{ plan.settleDeadline ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="指标体系">
            {{ plan.indicatorsFinalized ? '已定稿' : '未定稿' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card v-if="plan.years && plan.years.length > 0" shadow="never" class="info-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">年度进度</span>
            <el-tooltip
              v-if="canSettle && !isArchived && !allYearsLocked"
              content="全部年度核算锁定后开放"
              placement="left"
            >
              <span>
                <el-button size="small" disabled>五年核算</el-button>
              </span>
            </el-tooltip>
            <el-button
              v-else-if="canSettle && !isArchived"
              size="small"
              type="primary"
              :loading="finalSettling"
              @click="handleFinalSettle"
            >
              五年核算
            </el-button>
          </div>
        </template>
        <el-steps align-center>
          <el-step
            v-for="year in plan.years"
            :key="year.year"
            :title="`${year.year} 年${year.status === 'LOCKED' ? ' 🔒' : ''}`"
            :status="year.status === 'LOCKED' ? 'finish' : year.status === 'ACTIVE' ? 'process' : 'wait'"
          >
            <template #description>
              <StatusTag kind="PlanYearStatus" :value="year.status" />
              <div v-if="year.status === 'LOCKED' && year.settledAt" class="year-settled-at">
                核算于 {{ year.settledAt.slice(0, 10) }}
              </div>
              <div v-if="canSettle && !isArchived" class="year-actions">
                <el-button
                  v-if="year.status === 'NOT_ACTIVATED'"
                  type="primary"
                  size="small"
                  plain
                  :loading="activatingYear === year.year"
                  @click="handleActivateYear(year)"
                >
                  激活
                </el-button>
                <el-button
                  v-if="year.status === 'ACTIVE'"
                  type="warning"
                  size="small"
                  plain
                  :loading="settlingYear === year.year"
                  @click="handleSettleYear(year)"
                >
                  年度核算
                </el-button>
                <el-button
                  v-if="year.status === 'LOCKED'"
                  type="danger"
                  size="small"
                  plain
                  @click="openUnlockDialog(year)"
                >
                  解锁
                </el-button>
              </div>
            </template>
          </el-step>
        </el-steps>
      </el-card>

      <div class="kpi-row">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">{{ formatPercent(overviewAll?.cumulativeCompletionRate) }}</div>
          <div class="kpi-label">
            五年总完成率
            <el-tooltip content="五年累计口径：各指标「累计完成 ÷ 五年总量目标」的平均；五年目标即 100%" placement="top">
              <span class="kpi-tip">ⓘ</span>
            </el-tooltip>
          </div>
        </el-card>
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">
            {{ activeYear ? formatPercent(overview?.overallCompletionRate) : '—' }}
          </div>
          <ProgressWithTimeLine
            v-if="activeYear"
            class="kpi-progress"
            :rate="overview?.overallCompletionRate"
          />
          <div class="kpi-label">
            {{ activeYear ?? new Date().getFullYear() }} 年度完成率
            <el-tooltip content="当年完成率（计数制）" placement="top">
              <span class="kpi-tip">ⓘ</span>
            </el-tooltip>
          </div>
        </el-card>
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">
            <span class="text-danger">{{ overview?.constraintAchieved ?? '—' }}</span>
            <span class="kpi-total"> / {{ overview?.constraintTotal ?? '—' }}</span>
          </div>
          <div class="kpi-label">
            约束性达标
            <el-tooltip content="约束性指标：已达标任务数 / 任务总数" placement="top">
              <span class="kpi-tip">ⓘ</span>
            </el-tooltip>
          </div>
        </el-card>
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">
            <span class="text-teal">{{ overview?.developmentAchieved ?? '—' }}</span>
            <span class="kpi-total"> / {{ overview?.developmentTotal ?? '—' }}</span>
          </div>
          <div class="kpi-label">
            发展性达标
            <el-tooltip content="发展性指标：已达标任务数 / 任务总数" placement="top">
              <span class="kpi-tip">ⓘ</span>
            </el-tooltip>
          </div>
        </el-card>
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">{{ reviewPendingCount }}</div>
          <div class="kpi-label">
            待审核
            <el-tooltip content="待我审核的填报条数（来自待办汇总）" placement="top">
              <span class="kpi-tip">ⓘ</span>
            </el-tooltip>
          </div>
        </el-card>
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-value">{{ approvalPendingCount }}</div>
          <div class="kpi-label">
            调整审批中
            <el-tooltip content="待我处理的调整审批条数（来自待办汇总）" placement="top">
              <span class="kpi-tip">ⓘ</span>
            </el-tooltip>
          </div>
        </el-card>
      </div>

      <div class="stat-row">
        <el-card shadow="never" class="stat-card">
          <template #header><span class="card-title">A 级指标完成情况</span></template>
          <el-empty
            v-if="byIndicator.length === 0"
            description="暂无 A 级指标统计数据"
            :image-size="60"
          />
          <div v-for="item in byIndicator" :key="item.indicatorId" class="progress-row">
            <span class="progress-name" :title="`${item.code} ${item.name}`">{{ item.code }} {{ item.name }}</span>
            <el-progress
              :percentage="progressPercentage(item.completionRate)"
              :stroke-width="10"
              class="progress-bar"
            />
          </div>
        </el-card>
        <el-card shadow="never" class="stat-card">
          <template #header>
            <span class="card-title">执行单元完成率排名</span>
            <span v-if="isUnitView" class="card-tip">（仅本单元）</span>
          </template>
          <el-empty v-if="rankRows.length === 0" description="暂无单元统计数据" :image-size="60" />
          <div v-for="item in rankRows" :key="item.unit?.id" class="rank-row">
            <span class="rank-no" :class="{ 'is-top': item.rank <= 3 }">
              {{ item.rank }}
            </span>
            <span class="rank-name" :title="item.unit?.name">{{ item.unit?.name }}</span>
            <el-progress
              :percentage="progressPercentage(item.completionRate)"
              :stroke-width="10"
              class="progress-bar"
            />
          </div>
        </el-card>
      </div>

      <div class="triple-row">
        <el-card shadow="never" class="triple-card">
          <template #header><span class="card-title">我的待办</span></template>
          <el-empty v-if="topTodos.length === 0" description="暂无待办" :image-size="60" />
          <div
            v-for="todo in topTodos"
            :key="`${todo.type}-${todo.bizId}`"
            class="mini-item"
            @click="goTodo(todo)"
          >
            <el-tag size="small" effect="light" class="mini-tag">{{ todoGroupLabel(todo.type) }}</el-tag>
            <span class="mini-title" :title="todo.detail ?? todo.bizCode ?? ''">
              {{ todo.detail ?? todo.bizCode ?? '—' }}
            </span>
            <span class="mini-time">{{ formatRelativeTime(todo.todoSince) }}</span>
          </div>
        </el-card>
        <el-card shadow="never" class="triple-card">
          <template #header><span class="card-title">预警指标</span></template>
          <el-empty v-if="topWarnings.length === 0" description="暂无预警" :image-size="60" />
          <div v-for="w in topWarnings" :key="w.taskId" class="mini-item">
            <span
              class="warning-code"
              :class="warningLevel(w) === 'danger' ? 'text-danger' : 'text-warning'"
            >
              {{ w.taskCode }}
            </span>
            <span class="mini-title" :title="w.indicatorName">{{ w.indicatorName }}</span>
            <el-tag :type="warningLevel(w)" size="small" effect="light" class="mini-tag">
              {{ formatPercent(w.completionRate) }} / {{ formatPercent(w.timeProgress) }}
            </el-tag>
          </div>
        </el-card>
        <el-card shadow="never" class="triple-card">
          <template #header><span class="card-title">最近动态</span></template>
          <el-empty v-if="topNotifications.length === 0" description="暂无消息" :image-size="60" />
          <div
            v-for="item in topNotifications"
            :key="item.id"
            class="mini-item"
            @click="openNotification(item)"
          >
            <span class="unread-dot" :class="{ 'is-unread': !item.readAt }" />
            <el-tag size="small" effect="light" class="mini-tag">
              {{ enumLabel('NotificationType', item.type, userStore.enums) }}
            </el-tag>
            <span class="mini-title" :title="item.title">{{ item.title }}</span>
            <span class="mini-time">{{ formatRelativeTime(item.createdAt) }}</span>
          </div>
        </el-card>
      </div>

      <el-card v-if="lockedYears.length > 0 || settlement" shadow="never" class="info-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">年度核算结果</span>
            <el-select
              v-model="settlementYear"
              size="small"
              style="width: 140px"
              placeholder="选择年度"
              @change="loadSettlement"
            >
              <el-option v-for="y in lockedYears" :key="y" :label="`${y} 年`" :value="y" />
            </el-select>
          </div>
        </template>
        <div v-loading="settlementLoading">
          <template v-if="settlement">
            <div class="settle-summary">
              <span>核算时间：{{ settlement.settledAt ?? '—' }}</span>
              <span class="settle-tag"><StatusTag kind="TaskYearStatus" value="ACHIEVED" /> {{ settlementSummary.achieved }}</span>
              <span class="settle-tag"><StatusTag kind="TaskYearStatus" value="NOT_ACHIEVED" /> {{ settlementSummary.notAchieved }}</span>
              <span class="settle-tag"><StatusTag kind="TaskYearStatus" value="MAKEUP_PENDING" /> {{ settlementSummary.makeupPending }}</span>
              <span class="settle-tag"><StatusTag kind="TaskYearStatus" value="MADE_UP" /> {{ settlementSummary.madeUp }}</span>
              <span class="settle-total">共 {{ settlementSummary.total }} 条任务</span>
            </div>
            <el-table :data="settlement.items" border size="small" max-height="360">
              <el-table-column prop="taskCode" label="任务编号" width="130" />
              <el-table-column label="指标" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.indicator?.name ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="执行单元" width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.unit?.name ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="完成值" width="110" align="right">
                <template #default="{ row }">{{ row.actualValue ?? '—' }}</template>
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
          <el-empty v-else-if="!settlementLoading" description="选择已核算年度查看结果" :image-size="60" />
        </div>
      </el-card>

      <el-card shadow="never" class="info-card">
        <template #header><span class="card-title">里程碑管理</span></template>
        <MilestonePanel :plan-id="planId" :years="planYears" :readonly="isArchived" />
      </el-card>
    </template>
  </div>

  <el-dialog v-model="templateDialogVisible" title="另存为模板" width="480px">
    <el-form label-width="90px">
      <el-form-item label="模板名称" required>
        <el-input v-model="templateName" maxlength="100" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="templateDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="savingTemplate" @click="submitSaveAsTemplate">
        保存模板
      </el-button>
    </template>
  </el-dialog>

  <ReasonDialog
    v-model="unlockDialogVisible"
    :title="`解锁 ${unlockTargetYear ?? ''} 年度`"
    :target="`${plan?.name ?? ''} · ${unlockTargetYear ?? ''} 年度`"
    confirm-text="确认解锁"
    :loading="unlockingYear !== null"
    @confirm="submitUnlock"
  />
</template>

<style scoped>
.page-alert {
  margin-bottom: var(--space-4);
}

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

.info-card {
  margin-bottom: var(--space-4);
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

.year-settled-at {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.year-actions {
  margin-top: var(--space-2);
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.kpi-card {
  border-radius: var(--radius-card);
  text-align: center;
}

.kpi-value {
  font-family: var(--app-font-number);
  font-size: 32px;
  font-weight: 600;
  line-height: 40px;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
}

.kpi-progress {
  margin-top: var(--space-2);
}

.kpi-tip {
  cursor: help;
  color: var(--el-text-color-placeholder);
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

.text-warning {
  color: var(--el-color-warning);
}

.text-teal {
  color: var(--app-color-teal);
}

.stat-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.stat-card {
  border-radius: var(--radius-card);
}

.progress-row,
.rank-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.progress-name,
.rank-name {
  width: 140px;
  flex-shrink: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-no {
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

.rank-no.is-top {
  color: #fff;
  background: var(--el-color-primary);
}

.progress-bar {
  flex: 1;
}

.settle-summary {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  font-variant-numeric: tabular-nums;
}

.settle-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.settle-total {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.triple-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.triple-card {
  border-radius: var(--radius-card);
}

.mini-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 8px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.mini-item:last-child {
  border-bottom: none;
}

.mini-item:hover {
  background-color: var(--el-fill-color-light);
}

.mini-tag {
  flex-shrink: 0;
}

.mini-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.warning-code {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: transparent;
  flex-shrink: 0;
}

.unread-dot.is-unread {
  background-color: var(--el-color-danger);
}

@media (max-width: 1200px) {
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
  }

  .triple-row {
    grid-template-columns: 1fr;
  }
}
</style>
