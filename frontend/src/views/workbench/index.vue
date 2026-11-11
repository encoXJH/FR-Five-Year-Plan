<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Expand, Fold, WarningFilled } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import ProgressWithTimeLine from '@/components/ProgressWithTimeLine.vue'
import TaskDetailDrawer from './components/TaskDetailDrawer.vue'
import { listAllTasks, listTasks } from '@/api/task'
import { getStatsOverview } from '@/api/stats'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { listPlans } from '@/api/plan'
import type { PlanCard } from '@/types/plan'
import { useUserStore } from '@/stores/user'
import { formatCount, formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { buildWarningRequests, mergeVisibleWarnings } from '@/utils/stats'
import { averageTaskYearCompletion, canWriteTask, formatDeadline, primaryTaskIndicator, taskMaterialState } from '@/utils/task'
import { canApplyForTask } from '@/utils/adjustment'
import type { PageResult } from '@/types/api'
import type { TaskListItem, TaskQuery } from '@/types/task'
import type { TaskYearStatus } from '@/types/enums'
import type { WarningItem } from '@/types/stats'

interface StatusTab {
  key: string
  label: string
  query: Pick<TaskQuery, 'status' | 'yearStatus'>
}

/** 只保留日常处理所需的高频状态，其他状态直接在列表中查看。 */
const STATUS_TABS: StatusTab[] = [
  { key: 'all', label: '全部', query: {} },
  { key: 'todo', label: '待填报', query: { yearStatus: 'IN_PROGRESS' } },
  { key: 'review', label: '待审核', query: { yearStatus: 'PENDING_REVIEW' } },
  { key: 'completed', label: '已完成', query: { yearStatus: 'ACHIEVED' } },
]

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

const currentYear = new Date().getFullYear()

/** 学院负责人处理本单元任务；管理员 / 建设方向负责人为管理视角 */
const isUnitOwner = computed(() => userStore.roles.includes('UNIT_OWNER'))
const isAdminView = computed(() => !isUnitOwner.value && userStore.roles.includes('ADMIN'))

const unitOptions = ref<OrgUnit[]>([])
const planOptions = ref<PlanCard[]>([])

const filters = reactive({
  planId: '',
  unitId: '',
  year: currentYear as number | null,
  tab: 'all',
})

const yearOptions = computed<number[]>(() => {
  const list: number[] = []
  for (let year = currentYear - 1; year <= currentYear + 4; year += 1) {
    list.push(year)
  }
  return list
})

const loading = ref(false)
const page = ref<PageResult<TaskListItem>>({ items: [], total: 0, page: 1, pageSize: 20 })

const kpi = reactive({
  /** 当前可见任务总数（状态侧栏计数） */
  all: 0,
  /** 本年完成率：当前可见任务完成率的简单平均 */
  yearRate: null as number | null,
  todo: 0,
  review: 0,
  completed: 0,
})

/** 状态侧栏收起状态 */
const statusSidebarCollapsed = ref(false)

function statusCount(key: string): number {
  if (key === 'todo') return kpi.todo
  if (key === 'review') return kpi.review
  if (key === 'completed') return kpi.completed
  return kpi.all
}

/** 当前筛选范围内的预警明细，卡片数量与任务行标记共用。 */
const warningItems = ref<WarningItem[]>([])
const warningLoading = ref(false)
const warningFailed = ref(false)
const warningDialogVisible = ref(false)
const warningTaskIds = computed<ReadonlySet<string>>(() => new Set(warningItems.value.map((item) => item.taskId)))
const warningCardAriaLabel = computed(() => {
  if (warningLoading.value) return '正在统计当前筛选范围内的滞后预警'
  if (warningFailed.value) return '滞后预警加载失败，点击重试'
  return `滞后预警 ${warningItems.value.length} 项，点击查看明细`
})

const unitName = computed(() => {
  if (isUnitOwner.value) return userStore.userInfo?.unit?.name ?? '本单元'
  const found = unitOptions.value.find((unit) => unit.id === filters.unitId)
  return found?.name ?? '全部单元'
})

function currentQuery(extra: Partial<TaskQuery> = {}): TaskQuery {
  const tab = STATUS_TABS.find((item) => item.key === filters.tab) ?? STATUS_TABS[0]
  return {
    planId: filters.planId || undefined,
    unitId: filters.unitId || undefined,
    year: filters.year ?? undefined,
    ...tab.query,
    ...extra,
  }
}

let taskLoadSequence = 0

async function loadTasks(silent = false): Promise<void> {
  const sequence = ++taskLoadSequence
  if (!silent) loading.value = true
  try {
    const result = await listTasks(currentQuery({ page: page.value.page, pageSize: page.value.pageSize }))
    if (sequence === taskLoadSequence) page.value = result
  } finally {
    if (sequence === taskLoadSequence) loading.value = false
  }
}

/** 滞后预警：按任务实际年度读取统计总览，再收敛到当前可见任务。 */
let warningLoadSequence = 0

async function loadWarnings(items: TaskListItem[], silent = false): Promise<void> {
  const sequence = ++warningLoadSequence
  const requests = buildWarningRequests(items, filters.year)
  if (!silent) {
    warningItems.value = []
    warningFailed.value = false
    warningLoading.value = true
  }
  if (requests.length === 0) {
    if (sequence === warningLoadSequence) {
      warningItems.value = []
      warningFailed.value = false
      warningLoading.value = false
    }
    return
  }
  try {
    const overviews = await Promise.all(
      requests.map((request) => getStatsOverview(request.planId, request.year)),
    )
    if (sequence !== warningLoadSequence) return
    warningItems.value = mergeVisibleWarnings(items, overviews)
    warningFailed.value = false
  } catch {
    if (sequence !== warningLoadSequence) return
    warningItems.value = []
    warningFailed.value = true
  } finally {
    if (sequence === warningLoadSequence) warningLoading.value = false
  }
}

/** 统计任务状态、年度完成率及滞后预警。 */
let kpiLoadSequence = 0

async function loadKpi(silent = false): Promise<void> {
  const sequence = ++kpiLoadSequence
  warningLoadSequence += 1
  if (!silent) {
    warningItems.value = []
    warningFailed.value = false
    warningLoading.value = true
  }
  try {
    const base = { planId: filters.planId || undefined, unitId: filters.unitId || undefined, year: filters.year ?? undefined }
    const [batch, todo, review, completed] = await Promise.all([
      listAllTasks(base),
      listTasks({ ...base, yearStatus: 'IN_PROGRESS' as TaskYearStatus, page: 1, pageSize: 1 }),
      listTasks({ ...base, yearStatus: 'PENDING_REVIEW' as TaskYearStatus, page: 1, pageSize: 1 }),
      listTasks({ ...base, yearStatus: 'ACHIEVED' as TaskYearStatus, page: 1, pageSize: 1 }),
    ])
    if (sequence !== kpiLoadSequence) return
    kpi.todo = todo.total
    kpi.review = review.total
    kpi.completed = completed.total
    kpi.all = batch.length
    kpi.yearRate = averageTaskYearCompletion(batch)
    await loadWarnings(batch, silent)
  } catch {
    if (sequence !== kpiLoadSequence) return
    warningLoadSequence += 1
    warningItems.value = []
    warningFailed.value = true
    warningLoading.value = false
  }
}

function retryWarnings(): void {
  void loadKpi()
}

function openWarningDialog(): void {
  warningDialogVisible.value = true
  if (warningFailed.value) retryWarnings()
}

function applyFilters(): void {
  page.value.page = 1
  loadTasks()
  loadKpi()
}

function switchTab(): void {
  page.value.page = 1
  loadTasks()
}

function selectStatusTab(key: string): void {
  if (filters.tab === key) return
  filters.tab = key
  switchTab()
}

function changePage(next: number): void {
  page.value.page = next
  loadTasks()
}

function changePageSize(next: number): void {
  page.value.pageSize = next
  page.value.page = 1
  loadTasks()
}

// ---------- 行操作 ----------
const drawerVisible = ref(false)
const activeTaskId = ref<string | null>(null)
const drawerInitialYear = ref<number | null>(null)
const drawerInitialSubmissionId = ref<string | null>(null)
const drawerInitialTab = ref<'info' | 'submissions' | 'adjustments'>('info')

function openDetail(row: TaskListItem): void {
  activeTaskId.value = row.id
  drawerInitialYear.value = null
  drawerInitialSubmissionId.value = null
  drawerInitialTab.value = 'info'
  drawerVisible.value = true
}

function openWarningDetail(item: WarningItem): void {
  warningDialogVisible.value = false
  activeTaskId.value = item.taskId
  drawerInitialYear.value = item.year
  drawerInitialSubmissionId.value = null
  drawerInitialTab.value = 'info'
  drawerVisible.value = true
}

function openMaterials(row: TaskListItem): void {
  activeTaskId.value = row.id
  drawerInitialYear.value = row.displayYear ?? null
  drawerInitialSubmissionId.value = row.latestSubmissionId ?? null
  drawerInitialTab.value = 'submissions'
  drawerVisible.value = true
}

function indicatorLine(row: TaskListItem): string {
  return primaryTaskIndicator(row)?.code ?? '—'
}

function taskName(row: TaskListItem): string {
  return primaryTaskIndicator(row)?.name ?? '—'
}

function caliberOf(row: TaskListItem) {
  return primaryTaskIndicator(row)?.statCaliber ?? 'INCREMENT'
}

/** 填报入口：任务已下发且年度进行中（IN_PROGRESS）且有填报权限（P07 → P08 联动） */
function canSubmitRow(row: TaskListItem): boolean {
  return canWriteTask(row)
    && (row.status === 'DISTRIBUTED' || row.status === 'IN_PROGRESS')
    && filters.year === currentYear
    && row.yearStatus != null
    && ['NOT_ACTIVATED', 'IN_PROGRESS'].includes(row.yearStatus)
    && userStore.can('submission:submit')
}

function goSubmit(row: TaskListItem): void {
  if (!canSubmitRow(row)) {
    if (!canWriteTask(row)) ElMessage.warning('历史任务只读，不能填报')
    return
  }
  router.push({
    path: `/tasks/${row.id}/submit`,
    query: filters.year ? { year: filters.year } : undefined,
  })
}

/** 发起调整入口（P10）：INCREMENT / BOOLEAN 口径 + adjustment:apply 权限，任务未终结 */
function canApplyRow(row: TaskListItem): boolean {
  return (
    canWriteTask(row) &&
    userStore.can('adjustment:apply') &&
    canApplyForTask(row.status, primaryTaskIndicator(row)?.statCaliber, row.isHistorical)
  )
}

function goApply(row: TaskListItem): void {
  if (!canApplyRow(row)) {
    if (!canWriteTask(row)) ElMessage.warning('历史任务只读，不能发起调整')
    return
  }
  router.push({ path: '/adjustments/apply', query: { taskId: row.id } })
}

onMounted(() => {
  const linkedTaskId = typeof route.query.taskId === 'string' ? route.query.taskId : null
  if (linkedTaskId) {
    const linkedYear = typeof route.query.year === 'string' ? Number(route.query.year) : null
    if (linkedYear != null && Number.isInteger(linkedYear)) filters.year = linkedYear
    filters.tab = 'all'
    activeTaskId.value = linkedTaskId
    drawerVisible.value = true
  }
  loadTasks()
  loadKpi()
  listPlans()
    .then((list) => {
      planOptions.value = list
    })
    .catch(() => undefined)
  if (isAdminView.value) {
    listOrgUnits()
      .then((list) => {
        unitOptions.value = list
      })
      .catch(() => undefined)
  }
})

useAutoRefresh(
  async () => {
    await Promise.all([loadTasks(true), loadKpi(true)])
  },
  { skip: () => drawerVisible.value || warningDialogVisible.value },
)
</script>

<template>
  <div class="workbench-page">
    <div class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">五年规划 · 任务执行</span>
        <h1 class="page-title">{{ isAdminView ? '任务代填' : `${unitName} · 学院工作台` }}</h1>
        <p class="page-subtitle">
          {{ filters.year ? `${filters.year} 年` : '全部年度' }}<span aria-hidden="true">/</span>任务填报、佐证材料与完成进度统一处理
        </p>
      </div>
      <div class="page-plan-filter">
        <span class="page-plan-filter__label">当前计划</span>
        <el-select
          v-model="filters.planId"
          clearable
          filterable
          placeholder="全部计划"
          class="page-plan-filter__select"
          aria-label="按计划筛选"
          @change="applyFilters"
        >
          <el-option
            v-for="plan in planOptions"
            :key="plan.id"
            :label="plan.status === 'ARCHIVED' ? `${plan.name}（已归档）` : plan.name"
            :value="plan.id"
          />
        </el-select>
      </div>
    </div>

    <el-alert
      v-if="isAdminView && !filters.unitId"
      type="info"
      :closable="false"
      class="page-alert"
      title="任务代填模式：请先选择执行单元"
    />

    <section class="workbench-workspace" :class="{ 'is-status-collapsed': statusSidebarCollapsed }">
      <aside class="status-sidebar" :class="{ 'is-collapsed': statusSidebarCollapsed }">
        <div class="status-sidebar__header">
          <strong v-if="!statusSidebarCollapsed">任务状态</strong>
          <div class="status-sidebar__header-actions">
            <el-button
              class="status-sidebar__toggle"
              link
              type="primary"
              :aria-label="statusSidebarCollapsed ? '展开任务状态' : '收起任务状态'"
              :aria-expanded="!statusSidebarCollapsed"
              aria-controls="status-sidebar-content"
              :title="statusSidebarCollapsed ? '展开任务状态' : '收起任务状态'"
              @click="statusSidebarCollapsed = !statusSidebarCollapsed"
            >
              <el-icon><Expand v-if="statusSidebarCollapsed" /><Fold v-else /></el-icon>
            </el-button>
          </div>
        </div>
        <Transition name="status-sidebar-content">
          <div v-if="!statusSidebarCollapsed" id="status-sidebar-content" class="status-sidebar__body">
            <button
              v-for="tab in STATUS_TABS"
              :key="tab.key"
              type="button"
              class="status-nav-item"
              :class="{ 'is-active': filters.tab === tab.key }"
              :aria-pressed="filters.tab === tab.key"
              :data-status-key="tab.key"
              @click="selectStatusTab(tab.key)"
            >
              <span>{{ tab.label }}</span><span>{{ statusCount(tab.key) }}</span>
            </button>
          </div>
        </Transition>
      </aside>

      <div class="workbench-main">
        <div class="simple-filter">
          <el-select
            v-if="isAdminView"
            v-model="filters.unitId"
            clearable
            filterable
            placeholder="全部单元"
            class="simple-filter__unit"
            aria-label="按执行单元筛选"
            @change="applyFilters"
          >
            <el-option
              v-for="unit in unitOptions"
              :key="unit.id"
              :label="`${unit.code} ${unit.name}`"
              :value="unit.id"
            />
          </el-select>
          <el-select
            v-model="filters.year"
            clearable
            placeholder="全部年份"
            class="simple-filter__year"
            aria-label="按年度筛选"
            @change="applyFilters"
          >
            <el-option v-for="year in yearOptions" :key="year" :label="`${year} 年`" :value="year" />
          </el-select>
          <el-button type="primary" class="simple-filter__query" @click="applyFilters">查询</el-button>
        </div>

        <div class="workbench-summary">
          <div class="summary-card"><span>待填报</span><strong>{{ kpi.todo }}</strong></div>
          <div class="summary-card"><span>待审核</span><strong>{{ kpi.review }}</strong></div>
          <div class="summary-card"><span>本年完成率</span><strong>{{ formatPercent(kpi.yearRate) }}</strong></div>
          <button
            type="button"
            class="summary-card summary-warning"
            :class="{ 'is-active': warningItems.length > 0, 'is-failed': warningFailed }"
            :aria-label="warningCardAriaLabel"
            :aria-busy="warningLoading"
            @click="openWarningDialog"
          >
            <span class="summary-warning__title">
              <el-icon v-if="warningItems.length > 0"><WarningFilled /></el-icon>
              <span>滞后预警</span>
            </span>
            <strong>{{ warningLoading ? '…' : warningFailed ? '!' : warningItems.length }}</strong>
            <span class="summary-warning__hint">
              {{ warningLoading ? '正在统计…' : warningFailed ? '加载失败，点击重试' : warningItems.length > 0 ? '点击查看滞后任务' : '当前范围暂无预警' }}
            </span>
          </button>
        </div>

        <!-- 移动端（≤768px）：任务卡片单列堆叠（UI 规范 5.2），桌面端隐藏 -->
        <div v-loading="loading" class="task-cards">
        <el-empty
          v-if="!loading && page.items.length === 0"
          :image-size="80"
          description="当前筛选下暂无任务"
        />
        <div v-for="row in page.items" :key="`card-${row.id}`" class="task-card">
          <div class="task-card-header">
            <span class="mono task-card-code">{{ row.code }}</span>
            <StatusTag v-if="row.status === 'PENDING_DISTRIBUTION'" kind="TaskStatus" :value="row.status" />
            <StatusTag v-else-if="row.yearStatus" kind="TaskYearStatus" :value="row.yearStatus" />
            <StatusTag v-else kind="TaskStatus" :value="row.status" />
            <el-tag v-if="row.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
          </div>
          <div class="task-card-name">{{ taskName(row) }}</div>
          <div class="task-card-kv">
            <span>所属 {{ indicatorLine(row) }}</span>
            <span>五年目标 {{ formatTargetValue(row.totalTarget ?? null, caliberOf(row)) }}</span>
            <span>累计完成 {{ formatTargetValue(row.cumulativeActual ?? null, caliberOf(row)) }}</span>
            <span>本年目标 {{ formatTargetValue(row.yearTarget ?? null, caliberOf(row)) }}</span>
            <span>本年完成 {{ formatTargetValue(row.actualValue ?? null, caliberOf(row)) }}</span>
            <span :class="{ 'deadline-urgent': formatDeadline(row.deadline).urgent }">
              截止 {{ formatDeadline(row.deadline).text }}
            </span>
          </div>
          <ProgressWithTimeLine
            :rate="row.cumulativeRate ?? null"
            :lagging="warningTaskIds.has(row.id)"
          />
          <div
            class="task-card-materials"
            :class="{ 'is-missing': taskMaterialState(row).missingCount > 0 }"
          >
            <div class="task-card-material-heading">
              <span>佐证材料</span>
              <el-tag :type="taskMaterialState(row).tagType" size="small" effect="light">
                {{ taskMaterialState(row).label }}
              </el-tag>
              <span v-if="taskMaterialState(row).missingCount > 0" class="material-missing">
                缺 {{ taskMaterialState(row).missingCount }} 份
              </span>
            </div>
            <div class="task-card-material-actions">
              <el-button
                class="material-preview-button"
                plain
                :aria-label="`预览 ${taskName(row)} 的佐证材料，共 ${taskMaterialState(row).fileCount} 份`"
                @click="openMaterials(row)"
              >
                材料预览 · 共 {{ formatCount(taskMaterialState(row).fileCount) }} 份
              </el-button>
              <el-button
                v-if="taskMaterialState(row).missingCount > 0 && canSubmitRow(row)"
                type="danger"
                plain
                @click="goSubmit(row)"
              >
                去补材料
              </el-button>
            </div>
          </div>
          <div class="app-action-row task-card-actions">
            <el-button v-if="canSubmitRow(row)" type="primary" size="small" @click="goSubmit(row)">填报</el-button>
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canApplyRow(row)" type="warning" size="small" plain @click="goApply(row)">调整</el-button>
          </div>
        </div>
      </div>

      <div class="task-table">
        <el-table v-loading="loading" :data="page.items" class="workbench-table">
        <el-table-column label="任务" min-width="260" fixed="left">
          <template #default="{ row }">
            <div class="task-name" :title="taskName(row)">{{ taskName(row) }}</div>
            <div class="task-meta">
              <span class="mono">{{ row.code }}</span>
              <span>所属 {{ indicatorLine(row) }}</span>
              <el-tag v-if="row.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="五年目标" width="90" align="right">
          <template #default="{ row }">
            {{ formatTargetValue(row.totalTarget ?? null, caliberOf(row)) }}
          </template>
        </el-table-column>
        <el-table-column label="本年目标" width="90" align="right">
          <template #default="{ row }">
            {{ formatTargetValue(row.yearTarget ?? null, caliberOf(row)) }}
          </template>
        </el-table-column>
        <el-table-column label="累计完成" width="100" align="right">
          <template #default="{ row }">
            {{ formatTargetValue(row.cumulativeActual ?? null, caliberOf(row)) }}
          </template>
        </el-table-column>
        <el-table-column label="五年累计进度" min-width="170">
          <template #default="{ row }">
            <ProgressWithTimeLine
              :rate="row.cumulativeRate ?? null"
              :lagging="warningTaskIds.has(row.id)"
            />
          </template>
        </el-table-column>
        <el-table-column label="材料" width="148" align="center" header-align="center">
          <template #default="{ row }">
            <div class="material-cell" :class="{ 'is-missing': taskMaterialState(row).missingCount > 0 }">
              <el-button
                link
                type="primary"
                class="material-count"
                :title="`查看 ${row.displayYear ?? '当前'} 年度提交记录与佐证材料`"
                @click="openMaterials(row)"
              >
                共 {{ formatCount(taskMaterialState(row).fileCount) }} 份
              </el-button>
              <div class="material-status-line">
                <el-tag :type="taskMaterialState(row).tagType" size="small" effect="light">
                  {{ taskMaterialState(row).label }}
                </el-tag>
                <span v-if="taskMaterialState(row).missingCount > 0" class="material-missing">
                  缺 {{ taskMaterialState(row).missingCount }} 份
                </span>
              </div>
              <el-button
                v-if="taskMaterialState(row).missingCount > 0 && canSubmitRow(row)"
                link
                type="danger"
                class="material-fill"
                @click="goSubmit(row)"
              >
                去补材料
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <StatusTag v-if="row.status === 'PENDING_DISTRIBUTION'" kind="TaskStatus" :value="row.status" />
            <StatusTag v-else-if="row.yearStatus" kind="TaskYearStatus" :value="row.yearStatus" />
            <StatusTag v-else kind="TaskStatus" :value="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="截止时间" width="170">
          <template #default="{ row }">
            <span
              :class="{
                'deadline-overdue': formatDeadline(row.deadline).overdue,
                'deadline-urgent': formatDeadline(row.deadline).urgent && !formatDeadline(row.deadline).overdue,
                'empty-text': !row.deadline,
              }"
            >
              {{ formatDeadline(row.deadline).text }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="left">
          <template #default="{ row }">
            <el-button v-if="canSubmitRow(row)" link type="primary" @click="goSubmit(row)">填报</el-button>
            <el-button link @click="openDetail(row)">详情</el-button>
            <el-button v-if="canApplyRow(row)" link type="warning" @click="goApply(row)">调整</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :image-size="80"
            description="当前筛选下暂无任务"
          />
        </template>
      </el-table>
      </div>

      <div class="table-footer">
        共 {{ page.total }} 条任务；当前显示 {{ page.items.length }} 条；待填报 {{ kpi.todo }} / 待审核 {{ kpi.review }}
      </div>

      <div class="pager">
        <el-pagination
          :current-page="page.page"
          :page-size="page.pageSize"
          :total="page.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
      </div>
    </section>

    <el-dialog
      v-model="warningDialogVisible"
      title="滞后预警明细"
      width="min(860px, calc(100vw - 32px))"
      class="workbench-warning-dialog"
    >
      <div class="warning-dialog__intro">
        <strong>当前筛选范围</strong>
        <span>时间进度 − 年度完成率超过 50% 时触发预警</span>
      </div>
      <div v-loading="warningLoading" class="warning-dialog__body">
        <div v-if="warningFailed" class="warning-load-error">
          <el-alert type="error" title="预警加载失败，请稍后重试" :closable="false" show-icon />
          <el-button type="primary" plain @click="retryWarnings">重新加载</el-button>
        </div>
        <el-empty
          v-else-if="!warningLoading && warningItems.length === 0"
          :image-size="72"
          description="当前筛选范围暂无滞后预警"
        />
        <div v-else class="warning-list">
          <article v-for="item in warningItems" :key="item.taskId" class="warning-item">
            <div class="warning-item__content">
              <div class="warning-item__heading">
                <span class="mono warning-item__code">{{ item.taskCode }}</span>
                <el-tag type="danger" size="small" effect="light">落后 {{ formatPercent(item.lagGap) }}</el-tag>
              </div>
              <div class="warning-item__name">{{ item.indicatorName }}</div>
              <div class="warning-item__meta">{{ item.unit?.name ?? '未分配单元' }} · {{ item.year }} 年</div>
            </div>
            <div class="warning-item__metrics">
              <span>完成率<strong>{{ formatPercent(item.completionRate) }}</strong></span>
              <span>时间进度<strong>{{ formatPercent(item.timeProgress) }}</strong></span>
              <span>落后差值<strong class="warning-danger-text">{{ formatPercent(item.lagGap) }}</strong></span>
            </div>
            <el-button class="warning-item__action" type="primary" plain @click="openWarningDetail(item)">详情</el-button>
          </article>
        </div>
      </div>
    </el-dialog>

    <TaskDetailDrawer
      v-model="drawerVisible"
      :task-id="activeTaskId"
      :initial-year="drawerInitialYear"
      :initial-submission-id="drawerInitialSubmissionId"
      :initial-tab="drawerInitialTab"
    />
  </div>
</template>

<style scoped>
.workbench-page {
  --workbench-ink: #17233c;
  --workbench-muted: #6c7687;
  --workbench-line: #e4e9f0;
  --workbench-soft: #f8fafc;
  --workbench-paper: #ffffff;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  color: var(--workbench-ink);
}

.page-alert {
  margin-bottom: var(--space-4);
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: flex-start;
  gap: var(--space-4);
  margin-bottom: 18px;
  padding: 2px 0 18px;
  border-bottom: 1px solid var(--workbench-line);
}

.page-heading-copy {
  min-width: 0;
}

.page-eyebrow {
  display: block;
  margin-bottom: 5px;
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1.5px;
  line-height: 16px;
}

.page-title {
  margin: 0;
  font-size: 25px;
  font-weight: 650;
  line-height: 34px;
  letter-spacing: -0.4px;
  overflow-wrap: anywhere;
}

.page-subtitle {
  margin: 4px 0 0;
  color: var(--workbench-muted);
  font-size: 12px;
  line-height: 20px;
  overflow-wrap: anywhere;
}

.page-subtitle > span {
  padding: 0 7px;
  color: var(--el-border-color);
}

.workbench-workspace {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  flex: 1;
  gap: 22px;
  transition: grid-template-columns 0.2s ease;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.workbench-workspace.is-status-collapsed {
  grid-template-columns: 44px minmax(0, 1fr);
}

.status-sidebar {
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 10px;
  border: 1px solid var(--workbench-line);
  border-top: 3px solid var(--el-color-primary);
  border-radius: 8px;
  background: var(--workbench-paper);
}

.status-sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  padding: 0 10px 14px;
  border-bottom: 1px solid var(--workbench-line);
  font-size: 14px;
}

.status-sidebar__header strong {
  font-weight: 600;
  letter-spacing: 0.3px;
}

.status-sidebar__header-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.status-sidebar__header-actions :deep(.el-button) {
  margin-left: 0;
}

.status-sidebar__toggle {
  width: 30px;
  height: 30px;
  padding: 0;
}

.status-sidebar.is-collapsed {
  overflow: hidden;
  padding: 8px 4px;
}

.status-sidebar.is-collapsed .status-sidebar__header {
  justify-content: center;
  margin: 0;
  padding: 0;
  border-bottom: 0;
}

.status-sidebar__body {
  transform-origin: left top;
}

.status-sidebar-content-enter-active,
.status-sidebar-content-leave-active {
  transition: opacity 0.16s ease, transform 0.18s ease;
}

.status-sidebar-content-enter-from,
.status-sidebar-content-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}

.status-nav-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-height: 44px;
  padding: 11px 12px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--el-text-color-regular);
  font: inherit;
  font-size: 13px;
  line-height: 21px;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.status-nav-item > span:first-child {
  min-width: 0;
}

.status-nav-item > span:last-child {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 20px;
  padding: 0 5px;
  border-radius: 10px;
  background: var(--workbench-soft);
  color: var(--workbench-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.status-nav-item:hover {
  background: var(--workbench-soft);
  color: var(--el-color-primary);
}

.status-nav-item.is-active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.status-nav-item.is-active::before {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 0;
  width: 3px;
  border-radius: 2px;
  background: var(--el-color-primary);
  content: '';
}

.status-nav-item.is-active > span:last-child {
  background: var(--el-color-primary);
  color: #fff;
}

.status-nav-item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}

.workbench-main {
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  padding: 0 2px 12px 0;
}

.simple-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid var(--workbench-line);
  border-radius: 7px;
  background: var(--workbench-soft);
}

.simple-filter__unit {
  width: 220px;
  flex: 0 0 auto;
}

.page-plan-filter {
  flex: 0 1 480px;
  min-width: 320px;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
}

.page-plan-filter__label {
  display: block;
  margin-bottom: 5px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
}

.page-plan-filter__select {
  width: 100%;
}

.page-plan-filter__select :deep(.el-select__wrapper) {
  min-height: 38px;
  background: var(--workbench-paper);
}

.simple-filter__year {
  width: 130px;
  flex: 0 0 auto;
}

.simple-filter__query {
  min-width: 66px;
}

.workbench-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  margin-bottom: 14px;
  border: 1px solid var(--workbench-line);
  border-radius: 7px;
  background: var(--workbench-line);
}

.summary-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
  min-height: 86px;
  box-sizing: border-box;
  padding: 12px 14px;
  border: 0;
  border-radius: 0;
  background: var(--workbench-paper);
  color: var(--workbench-ink);
  font: inherit;
  text-align: left;
}

button.summary-card {
  cursor: pointer;
}

.summary-card > span {
  color: var(--workbench-muted);
  font-size: 11px;
}

.summary-card strong {
  color: var(--workbench-ink);
  font-size: 16px;
  line-height: 22px;
  font-variant-numeric: tabular-nums;
}

.summary-warning {
  transition: background-color 0.16s ease;
}

.summary-warning:hover {
  background: var(--workbench-soft);
}

.summary-warning.is-active,
.summary-warning.is-active:hover {
  background: var(--el-color-danger-light-9);
}

.summary-warning.is-active strong,
.summary-warning.is-active .el-icon,
.summary-warning.is-failed strong {
  color: var(--el-color-danger);
}

.summary-warning__title {
  display: flex;
  align-items: center;
  gap: 5px;
}

.summary-warning__title .el-icon {
  color: var(--el-color-danger);
  font-size: 15px;
}

.summary-card .summary-warning__hint {
  color: var(--workbench-muted);
  font-size: 11px;
  line-height: 16px;
}

.summary-warning.is-active .summary-warning__hint,
.summary-warning.is-failed .summary-warning__hint {
  color: var(--el-color-danger);
}

.summary-warning:focus-visible {
  position: relative;
  z-index: 1;
  outline: 2px solid var(--el-color-primary);
  outline-offset: -3px;
}

.warning-dialog__intro {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--workbench-soft);
  font-size: 12px;
  line-height: 20px;
}

.warning-dialog__intro strong {
  color: var(--workbench-ink);
}

.warning-dialog__intro span {
  color: var(--workbench-muted);
}

.warning-dialog__body {
  min-height: 180px;
}

.warning-load-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding-top: 24px;
}

.warning-load-error .el-alert {
  width: 100%;
}

.warning-list {
  display: grid;
  gap: 10px;
  max-height: min(56vh, 520px);
  overflow-y: auto;
  padding-right: 2px;
}

.warning-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 16px;
  padding: 14px;
  border: 1px solid var(--el-color-danger-light-7);
  border-radius: 7px;
  background: var(--el-color-danger-light-9);
}

.warning-item__content {
  min-width: 0;
}

.warning-item__heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.warning-item__code {
  color: var(--workbench-muted);
  font-size: 12px;
}

.warning-item__name {
  margin-top: 5px;
  color: var(--workbench-ink);
  font-size: 14px;
  font-weight: 600;
  line-height: 21px;
  overflow-wrap: anywhere;
}

.warning-item__meta {
  margin-top: 3px;
  color: var(--workbench-muted);
  font-size: 12px;
  line-height: 18px;
}

.warning-item__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(64px, auto));
  gap: 12px;
}

.warning-item__metrics > span {
  display: flex;
  flex-direction: column;
  gap: 3px;
  color: var(--workbench-muted);
  font-size: 11px;
  white-space: nowrap;
}

.warning-item__metrics strong {
  color: var(--workbench-ink);
  font-size: 14px;
  line-height: 20px;
  font-variant-numeric: tabular-nums;
}

.warning-item__metrics .warning-danger-text {
  color: var(--el-color-danger);
}

.warning-item__action {
  min-width: 64px;
  margin: 0;
}

.task-table {
  min-width: 0;
  max-width: 100%;
}

.workbench-table {
  width: 100%;
  color: var(--workbench-ink);
  font-variant-numeric: tabular-nums;
}

.workbench-table :deep(.el-table__inner-wrapper::before),
.workbench-table :deep(.el-table__border-left-patch) {
  display: none;
}

.workbench-table :deep(.el-table__header th) {
  height: 40px;
  padding: 0;
  border-bottom: 1px solid #cfd7e3;
  background: transparent;
  color: var(--workbench-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.4px;
}

.workbench-table :deep(.el-table__body td) {
  padding: 14px 0;
  border-bottom: 1px solid var(--workbench-line);
  background: transparent;
}

.workbench-table :deep(.el-table__body tr:hover > td) {
  background: #fafbfc;
}

.table-footer {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--workbench-line);
  color: var(--workbench-muted);
  font-size: 12px;
  line-height: 20px;
}

.mono {
  font-family: var(--app-font-mono);
}

.task-name {
  overflow: hidden;
  font-weight: 600;
  line-height: 20px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.task-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) var(--space-2);
  margin-top: var(--space-1);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.material-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  min-width: 0;
  font-variant-numeric: tabular-nums;
}

.material-count {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.material-status-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 3px 6px;
}

.material-missing {
  color: var(--el-color-danger);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.material-fill {
  min-height: 22px;
  margin: 0;
  padding: 0;
  font-size: 12px;
}

.empty-text {
  color: var(--el-text-color-placeholder);
}

.deadline-urgent {
  color: var(--el-color-warning);
  font-weight: 600;
}

.deadline-overdue {
  color: var(--el-color-danger);
  font-weight: 600;
}

.pager {
  display: flex;
  justify-content: flex-end;
  min-width: 0;
  margin-top: var(--space-4);
}

.pager :deep(.el-pagination) {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--space-2) 0;
  min-width: 0;
  white-space: normal;
}

/* 移动端任务卡片：默认隐藏，仅 ≤768px 显示 */
.task-cards {
  display: none;
}

/* 移动端（≤768px，UI 规范 5.2）：表格改卡片单列堆叠 */
@media (max-width: 768px) {
  .workbench-page {
    display: block;
    height: auto;
  }

  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .page-title {
    font-size: 23px;
  }

  .workbench-workspace {
    display: block;
    overflow: visible;
  }

  .status-sidebar {
    display: flex;
    gap: 8px;
    margin-bottom: 14px;
    padding: 10px;
    overflow-x: auto;
    border: 1px solid var(--workbench-line);
    border-top: 3px solid var(--el-color-primary);
    border-radius: 7px;
  }

  .status-sidebar__header {
    flex: 0 0 auto;
    align-items: flex-start;
    flex-direction: column;
    gap: 2px;
    margin: 0;
    padding: 4px 8px;
    border-bottom: 0;
  }

  .status-sidebar__body {
    display: flex;
    flex: 1 0 auto;
    align-items: center;
    gap: 8px;
    min-width: max-content;
  }

  .status-sidebar.is-collapsed {
    width: 100%;
    padding: 6px 10px;
  }

  .status-sidebar.is-collapsed .status-sidebar__header {
    align-items: center;
    width: 100%;
    padding: 0;
  }

  .status-nav-item {
    flex: 0 0 auto;
    width: auto;
    min-height: 40px;
    white-space: nowrap;
  }

  .workbench-main {
    overflow: visible;
    padding: 0;
  }

  .simple-filter {
    flex-wrap: wrap;
  }

  .simple-filter__unit {
    flex: 1 1 100%;
    width: 100%;
  }

  .page-plan-filter {
    flex: 1 1 100%;
    width: 100%;
    min-width: 0;
  }

  .simple-filter__year {
    flex: 1 1 130px;
    width: auto;
  }

  .workbench-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .summary-card {
    min-height: 84px;
    padding: 12px;
  }

  .workbench-summary strong {
    font-size: 15px;
  }

  .warning-list {
    max-height: 60vh;
  }

  .warning-item {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }

  .warning-item__metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    width: 100%;
  }

  .warning-item__action {
    width: 100%;
    min-height: 44px;
  }

  .task-table {
    display: none;
  }

  .task-cards {
    display: block;
  }

  .task-card {
    width: 100%;
    min-width: 0;
    max-width: 100%;
    box-sizing: border-box;
    padding: 14px;
    margin-bottom: var(--space-3);
    border: 1px solid var(--workbench-line);
    border-radius: 8px;
    background-color: var(--el-bg-color);
  }

  .task-card-header {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    min-width: 0;
  }

  .task-card-code {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .task-card-name {
    min-width: 0;
    margin-top: var(--space-1);
    font-size: 15px;
    font-weight: 600;
    line-height: 22px;
    overflow-wrap: anywhere;
  }

  .task-card-kv {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-3);
    margin: var(--space-2) 0;
    font-size: 13px;
    color: var(--el-text-color-regular);
    font-variant-numeric: tabular-nums;
  }

  .task-card-materials {
    min-width: 0;
    margin-top: var(--space-3);
    padding: 10px;
    border: 1px solid var(--workbench-line);
    border-radius: 6px;
    background: var(--workbench-soft);
  }

  .task-card-materials.is-missing {
    border-color: var(--el-color-danger-light-7);
    background: var(--el-color-danger-light-9);
  }

  .task-card-material-heading {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    color: var(--workbench-muted);
    font-size: 13px;
  }

  .task-card-material-actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    min-width: 0;
    margin-top: var(--space-2);
  }

  .task-card-material-actions .el-button {
    flex: 1 1 130px;
    min-width: 0;
    min-height: 44px;
    margin: 0;
    white-space: normal;
  }

  .task-card-actions {
    display: flex;
    flex-wrap: wrap;
    width: 100%;
    gap: var(--space-2);
    margin: var(--space-2) 0 0;
  }

  .task-card-actions .el-button {
    flex: 1 1 0;
    width: 100%;
    min-width: 0;
    min-height: 44px;
    margin: 0;
  }

  .table-footer {
    display: none;
  }

  .pager {
    justify-content: center;
  }

  .pager :deep(.el-pagination) {
    justify-content: center;
  }

  .pager :deep(.el-pagination__total) {
    flex: 0 0 100%;
    margin-right: 0;
    text-align: center;
  }

  .pager :deep(.el-pagination__sizes),
  .pager :deep(.el-pagination__jump) {
    margin-right: 0;
    margin-left: 0;
  }
}

@media (max-width: 420px) {
  .simple-filter {
    gap: 8px;
    padding: 10px;
  }

  .status-sidebar {
    padding: 8px;
  }

  .summary-card {
    padding: 10px;
  }
}
</style>
