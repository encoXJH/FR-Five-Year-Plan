<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import ProgressWithTimeLine from '@/components/ProgressWithTimeLine.vue'
import TaskDetailDrawer from './components/TaskDetailDrawer.vue'
import { listAllTasks, listTasks } from '@/api/task'
import { getStatsOverview } from '@/api/stats'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { useUserStore } from '@/stores/user'
import { formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import { canWriteTask, formatDeadline, primaryTaskIndicator } from '@/utils/task'
import { canApplyForTask } from '@/utils/adjustment'
import type { PageResult } from '@/types/api'
import type { TaskListItem, TaskQuery } from '@/types/task'
import type { TaskYearStatus } from '@/types/enums'

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
]

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

const currentYear = new Date().getFullYear()

/** 学院负责人处理本单元任务；管理员 / 校级负责人为管理视角 */
const isUnitOwner = computed(() => userStore.roles.includes('UNIT_OWNER'))
const isAdminView = computed(() => !isUnitOwner.value && userStore.roles.includes('ADMIN'))

const unitOptions = ref<OrgUnit[]>([])

const filters = reactive({
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
  /** 本年完成率：当前可见任务完成率的简单平均 */
  yearRate: null as number | null,
  todo: 0,
  review: 0,
})

/** 滞后预警任务 id 集合（overview.warnings） */
const warningTaskIds = ref<ReadonlySet<string>>(new Set())

const unitName = computed(() => {
  if (isUnitOwner.value) return userStore.userInfo?.unit?.name ?? '本单元'
  const found = unitOptions.value.find((unit) => unit.id === filters.unitId)
  return found?.name ?? '全部单元'
})

function currentQuery(extra: Partial<TaskQuery> = {}): TaskQuery {
  const tab = STATUS_TABS.find((item) => item.key === filters.tab) ?? STATUS_TABS[0]
  return {
    unitId: filters.unitId || undefined,
    year: filters.year ?? undefined,
    ...tab.query,
    ...extra,
  }
}

async function loadTasks(): Promise<void> {
  loading.value = true
  try {
    page.value = await listTasks(currentQuery({ page: page.value.page, pageSize: page.value.pageSize }))
  } finally {
    loading.value = false
  }
}

/** 滞后预警：跨计划读取全部关联计划的 stats overview 后合并。 */
let warningLoadSequence = 0

async function loadWarnings(items: TaskListItem[]): Promise<void> {
  const sequence = ++warningLoadSequence
  const planIds = [...new Set(items.map((item) => item.planId).filter(Boolean))]
  if (planIds.length === 0) {
    warningTaskIds.value = new Set()
    return
  }
  const overviews = await Promise.all(
    planIds.map((planId) => getStatsOverview(planId, filters.year ?? currentYear).catch(() => null)),
  )
  if (sequence !== warningLoadSequence) return
  warningTaskIds.value = new Set(
    overviews.flatMap((overview) => overview?.warnings.map((warning) => warning.taskId) ?? []),
  )
}

/** 仅统计保留的三项核心指标。 */
async function loadKpi(): Promise<void> {
  try {
    const base = { unitId: filters.unitId || undefined, year: filters.year ?? undefined }
    const [batch, todo, review] = await Promise.all([
      listAllTasks(base),
      listTasks({ ...base, yearStatus: 'IN_PROGRESS' as TaskYearStatus, page: 1, pageSize: 1 }),
      listTasks({ ...base, yearStatus: 'PENDING_REVIEW' as TaskYearStatus, page: 1, pageSize: 1 }),
    ])
    kpi.todo = todo.total
    kpi.review = review.total
    const rates = batch
      .map((item) => item.completionRate)
      .filter((rate): rate is number => typeof rate === 'number')
    kpi.yearRate = rates.length > 0 ? rates.reduce((sum, rate) => sum + rate, 0) / rates.length : null
    await loadWarnings(batch)
  } catch {
    // KPI 失败不阻塞列表
  }
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

function openDetail(row: TaskListItem): void {
  activeTaskId.value = row.id
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

/** 填报入口：年度进行中（IN_PROGRESS）且有填报权限（P07 → P08 联动） */
function canSubmitRow(row: TaskListItem): boolean {
  return canWriteTask(row)
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
  if (isAdminView.value) {
    listOrgUnits()
      .then((list) => {
        unitOptions.value = list
      })
      .catch(() => undefined)
  }
})
</script>

<template>
  <div class="workbench-page">
    <div class="page-header">
      <h1 class="page-title">{{ isAdminView ? '任务代填' : `${unitName} · 学院工作台` }}</h1>
    </div>

    <el-form inline class="workbench-filter" @submit.prevent>
      <el-form-item v-if="isAdminView" label="执行单元">
        <el-select
          v-model="filters.unitId"
          clearable
          filterable
          placeholder="全部单元"
          class="unit-select"
          @change="applyFilters"
        >
          <el-option
            v-for="unit in unitOptions"
            :key="unit.id"
            :label="`${unit.code} ${unit.name}`"
            :value="unit.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="年度">
        <el-select
          v-model="filters.year"
          clearable
          placeholder="全部年份"
          class="year-select"
          @change="applyFilters"
        >
          <el-option v-for="year in yearOptions" :key="year" :label="`${year} 年`" :value="year" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="isAdminView && !filters.unitId"
      type="info"
      :closable="false"
      class="page-alert"
      title="任务代填模式：请先选择执行单元"
    />

    <div class="kpi-row">
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ kpi.todo }}</div>
        <div class="kpi-label">待填报</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ kpi.review }}</div>
        <div class="kpi-label">待审核</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ formatPercent(kpi.yearRate) }}</div>
        <div class="kpi-label">本年完成率</div>
      </el-card>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="filters.tab" @tab-change="switchTab">
        <el-tab-pane v-for="tab in STATUS_TABS" :key="tab.key" :label="tab.label" :name="tab.key" />
      </el-tabs>

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
            <StatusTag v-if="row.yearStatus" kind="TaskYearStatus" :value="row.yearStatus" />
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
          <div class="app-action-row task-card-actions">
            <el-button v-if="canSubmitRow(row)" type="primary" size="small" @click="goSubmit(row)">填报</el-button>
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canApplyRow(row)" type="warning" size="small" plain @click="goApply(row)">调整</el-button>
          </div>
        </div>
      </div>

      <div class="task-table">
        <el-table v-loading="loading" :data="page.items" border size="small">
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
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <StatusTag v-if="row.yearStatus" kind="TaskYearStatus" :value="row.yearStatus" />
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
    </el-card>

    <TaskDetailDrawer
      v-model="drawerVisible"
      :task-id="activeTaskId"
    />
  </div>
</template>

<style scoped>
.workbench-page {
  min-width: 0;
  max-width: 100%;
}

.page-alert {
  margin-bottom: var(--space-4);
}

.page-header {
  margin-bottom: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  overflow-wrap: anywhere;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.kpi-card {
  min-width: 0;
  border-radius: var(--radius-card);
  text-align: center;
}

.kpi-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 32px;
  font-variant-numeric: tabular-nums;
}

.kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: var(--space-1);
}

.workbench-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 0 var(--space-3);
  margin-bottom: var(--space-4);
}

.workbench-filter :deep(.el-form-item) {
  min-width: 0;
  margin: 0;
}

.unit-select {
  width: 220px;
  max-width: 100%;
}

.year-select {
  width: 120px;
  max-width: 100%;
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
  .kpi-row {
    gap: var(--space-2);
  }

  .kpi-value {
    font-size: 18px;
    line-height: 26px;
  }

  .workbench-filter {
    width: 100%;
  }

  .task-table {
    display: none;
  }

  .task-cards {
    display: block;
  }

  .task-card {
    padding: 14px;
    margin-bottom: var(--space-3);
    border: 1px solid var(--el-border-color-light);
    border-radius: 8px;
    background-color: var(--el-bg-color);
  }

  .task-card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }

  .task-card-code {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .task-card-name {
    margin-top: var(--space-1);
    font-size: 15px;
    font-weight: 600;
    line-height: 22px;
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

  .task-card-actions {
    display: flex;
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

@media (max-width: 600px) {
  .workbench-filter :deep(.el-form-item) {
    display: block;
    flex: 1 1 180px;
  }

  .workbench-filter :deep(.el-form-item__label) {
    height: auto;
    margin-bottom: var(--space-1);
    line-height: 20px;
  }

  .workbench-filter :deep(.el-form-item__content),
  .unit-select,
  .year-select {
    width: 100%;
    margin-left: 0 !important;
  }

  .kpi-card :deep(.el-card__body) {
    padding: var(--space-3) var(--space-2);
  }

  .kpi-label {
    overflow-wrap: anywhere;
  }
}
</style>
