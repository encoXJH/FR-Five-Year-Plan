<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import ProgressWithTimeLine from '@/components/ProgressWithTimeLine.vue'
import TaskDetailDrawer from './components/TaskDetailDrawer.vue'
import { acceptTask, getTask, listTasks } from '@/api/task'
import { listSubmissions } from '@/api/submission'
import { getStatsOverview } from '@/api/stats'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import { canWriteTask, formatDeadline } from '@/utils/task'
import { canApplyForTask } from '@/utils/adjustment'
import type { PageResult } from '@/types/api'
import type { TaskListItem, TaskQuery } from '@/types/task'
import type { TaskStatus, TaskYearStatus } from '@/types/enums'

interface StatusTab {
  key: string
  label: string
  query: Pick<TaskQuery, 'status' | 'yearStatus'>
}

/** 状态快捷过滤（GET /tasks 支持 status / yearStatus；rejected 为填报级，前端近似过滤） */
const STATUS_TABS: StatusTab[] = [
  { key: 'all', label: '全部', query: {} },
  { key: 'todo', label: '待填报', query: { yearStatus: 'IN_PROGRESS' } },
  { key: 'review', label: '待审核', query: { yearStatus: 'PENDING_REVIEW' } },
  { key: 'rejected', label: '已驳回', query: {} },
  { key: 'achieved', label: '已达标', query: { yearStatus: 'ACHIEVED' } },
  { key: 'notAchieved', label: '未达标', query: { yearStatus: 'NOT_ACHIEVED' } },
  { key: 'adjusting', label: '调整中', query: { yearStatus: 'ADJUSTING' } },
  { key: 'accept', label: '待确认接收', query: { status: 'DISTRIBUTED' } },
]

const userStore = useUserStore()
const router = useRouter()

const currentYear = new Date().getFullYear()

/** 学院负责人（UNIT_OWNER）才有确认接收按钮；管理员 / 校级负责人为只读视角 */
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
  total: 0,
  /** 五年累计完成率：任务累计完成率的简单平均（前端统计，接口无分组聚合） */
  cumulativeRate: null as number | null,
  /** 本年完成率：任务完成率的简单平均（前端统计，接口无分组聚合） */
  yearRate: null as number | null,
  todo: 0,
  review: 0,
  /** 被驳回：填报级口径，由 loadRejected 近似统计 */
  rejected: null as number | null,
  adjusting: 0,
  pendingAccept: 0,
})

/** 工作台年度对应的时间进度（stats overview?year=，一次获取） */
const timeProgress = ref<number | null>(null)
/** 滞后预警任务 id 集合（overview.warnings） */
const warningTaskIds = ref<ReadonlySet<string>>(new Set())

const isRejectedTab = computed(() => filters.tab === 'rejected')

/** 已驳回 Tab 的结果集（客户端近似过滤，见 loadRejected） */
const rejectedItems = ref<TaskListItem[]>([])
const rejectedLoading = ref(false)

const displayItems = computed(() => (isRejectedTab.value ? rejectedItems.value : page.value.items))
const tableLoading = computed(() => (isRejectedTab.value ? rejectedLoading.value : loading.value))

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
  if (isRejectedTab.value) {
    await loadRejected()
    return
  }
  loading.value = true
  try {
    page.value = await listTasks(currentQuery({ page: page.value.page, pageSize: page.value.pageSize }))
    loadTimeProgress(page.value.items)
  } finally {
    loading.value = false
  }
}

/** 时间进度 + 滞后预警：取当前列表所属计划的 stats overview（一年一次） */
async function loadTimeProgress(items: TaskListItem[]): Promise<void> {
  const planId = items[0]?.planId
  if (!planId) {
    timeProgress.value = null
    warningTaskIds.value = new Set()
    return
  }
  try {
    const overview = await getStatsOverview(planId, filters.year ?? currentYear)
    timeProgress.value = overview.timeProgress
    warningTaskIds.value = new Set(overview.warnings.map((w) => w.taskId))
  } catch {
    timeProgress.value = null
    warningTaskIds.value = new Set()
  }
}

/**
 * 已驳回：驳回是填报级状态，任务行无 rejectedCount 字段（后端契约），
 * 近似实现为「拉取前 100 条任务 → 逐任务查填报 → 存在 REJECTED 记录即命中」。
 */
async function loadRejected(): Promise<void> {
  rejectedLoading.value = true
  try {
    const batch = await listTasks({
      unitId: filters.unitId || undefined,
      year: filters.year ?? undefined,
      page: 1,
      pageSize: 100,
    })
    const checks = await Promise.all(
      batch.items.map(async (task) => {
        try {
          const subs = await listSubmissions(task.id)
          const hit = subs.some(
            (s) => s.status === 'REJECTED' && (filters.year === null || s.year === filters.year),
          )
          return hit ? task : null
        } catch {
          return null
        }
      }),
    )
    rejectedItems.value = checks.filter((task): task is TaskListItem => task !== null)
    kpi.rejected = rejectedItems.value.length
    loadTimeProgress(batch.items)
  } finally {
    rejectedLoading.value = false
  }
}

/** KPI 统计：批次拉取（上限 200 条）在前端聚合，接口未提供分组计数 */
async function loadKpi(): Promise<void> {
  try {
    const base = { unitId: filters.unitId || undefined, year: filters.year ?? undefined }
    const [batch, todo, review, adjusting, pendingAccept] = await Promise.all([
      listTasks({ ...base, page: 1, pageSize: 200 }),
      listTasks({ ...base, yearStatus: 'IN_PROGRESS' as TaskYearStatus, page: 1, pageSize: 1 }),
      listTasks({ ...base, yearStatus: 'PENDING_REVIEW' as TaskYearStatus, page: 1, pageSize: 1 }),
      listTasks({ ...base, yearStatus: 'ADJUSTING' as TaskYearStatus, page: 1, pageSize: 1 }),
      listTasks({ ...base, status: 'DISTRIBUTED' as TaskStatus, page: 1, pageSize: 1 }),
    ])
    kpi.total = batch.total
    kpi.todo = todo.total
    kpi.review = review.total
    kpi.adjusting = adjusting.total
    kpi.pendingAccept = pendingAccept.total
    const rates = batch.items
      .map((item) => item.completionRate)
      .filter((rate): rate is number => typeof rate === 'number')
    kpi.yearRate = rates.length > 0 ? rates.reduce((sum, rate) => sum + rate, 0) / rates.length : null
    const cumRates = batch.items
      .map((item) => item.cumulativeRate)
      .filter((rate): rate is number => typeof rate === 'number')
    kpi.cumulativeRate = cumRates.length > 0 ? cumRates.reduce((sum, rate) => sum + rate, 0) / cumRates.length : null
  } catch {
    // KPI 失败不阻塞列表
  }
}

function applyFilters(): void {
  page.value.page = 1
  if (!isRejectedTab.value) {
    rejectedItems.value = []
    kpi.rejected = null
  }
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

const acceptingId = ref('')

async function handleAccept(row: TaskListItem): Promise<void> {
  if (!canWriteTask(row)) {
    ElMessage.warning('历史任务只读，不能确认接收')
    return
  }
  await ElMessageBox.confirm(
    `确认接收任务「${row.code} ${row.indicator?.name ?? ''}」？接收后任务进入执行中。`,
    '确认接收',
    { confirmButtonText: '确认接收', cancelButtonText: '取消' },
  )
  acceptingId.value = row.id
  try {
    // 列表行无 version，取详情版本号做乐观锁
    const detail = await getTask(row.id)
    if (!canWriteTask(detail)) {
      ElMessage.warning('历史任务只读，不能确认接收')
      return
    }
    await acceptTask(row.id, detail.version, newIdempotencyKey())
    ElMessage.success(`任务「${row.code}」已接收`)
    await loadTasks()
    loadKpi()
  } finally {
    acceptingId.value = ''
  }
}

function indicatorLine(row: TaskListItem): string {
  const indicator = row.indicator
  if (!indicator) return '—'
  return row.topIndicator ? `${row.topIndicator.code} / ${indicator.code}` : indicator.code
}

function caliberOf(row: TaskListItem) {
  return row.indicator?.statCaliber ?? 'INCREMENT'
}

function canAcceptRow(row: TaskListItem): boolean {
  return canWriteTask(row) && isUnitOwner.value && row.status === 'DISTRIBUTED'
}

/** 填报入口：年度进行中（IN_PROGRESS）且有填报权限（P07 → P08 联动） */
function canSubmitRow(row: TaskListItem): boolean {
  return canWriteTask(row) && row.yearStatus === 'IN_PROGRESS' && userStore.can('submission:submit')
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
    canApplyForTask(row.status, row.indicator?.statCaliber, row.isHistorical)
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
  <div>
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ isAdminView ? '任务代填' : `${unitName} · 学院工作台` }}</h1>
        <p class="page-subtitle">
          {{ isAdminView ? '请先选择执行单元，再为对应学院代填' : '本单元接收的全部 B 任务' }}
        </p>
      </div>
    </div>

    <el-alert
      v-if="isAdminView && !filters.unitId"
      type="info"
      :closable="false"
      class="page-alert"
      title="任务代填模式：请先在下方筛选中选择执行单元"
    />

    <el-alert
      v-if="isUnitOwner && kpi.pendingAccept > 0"
      type="warning"
      :closable="false"
      class="page-alert"
      :title="`您有 ${kpi.pendingAccept} 条任务待确认接收，请尽快处理`"
    />

    <div class="kpi-row">
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ kpi.total }}</div>
        <div class="kpi-label">任务数</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ formatPercent(kpi.cumulativeRate) }}</div>
        <div class="kpi-label">
          五年累计完成率
          <el-tooltip content="五年累计口径：累计完成 ÷ 五年总量目标（任务平均）；五年目标即 100%" placement="top">
            <span class="kpi-tip">ⓘ</span>
          </el-tooltip>
        </div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ formatPercent(kpi.yearRate) }}</div>
        <div class="kpi-label">本年完成率</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ kpi.todo }}</div>
        <div class="kpi-label">待填报</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ kpi.review }}</div>
        <div class="kpi-label">待审核</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ kpi.rejected ?? '—' }}</div>
        <div class="kpi-label">被驳回</div>
      </el-card>
      <el-card shadow="never" class="kpi-card">
        <div class="kpi-value">{{ kpi.adjusting }}</div>
        <div class="kpi-label">调整中</div>
      </el-card>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item v-if="isAdminView" label="执行单元">
          <el-select
            v-model="filters.unitId"
            clearable
            filterable
            placeholder="全部单元"
            style="width: 220px"
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
            style="width: 120px"
            @change="applyFilters"
          >
            <el-option v-for="year in yearOptions" :key="year" :label="`${year} 年`" :value="year" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="applyFilters">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="filters.tab" @tab-change="switchTab">
        <el-tab-pane v-for="tab in STATUS_TABS" :key="tab.key" :label="tab.label" :name="tab.key" />
      </el-tabs>

      <!-- 移动端（≤768px）：任务卡片单列堆叠（UI 规范 5.2），桌面端隐藏 -->
      <div v-loading="tableLoading" class="task-cards">
        <el-empty
          v-if="!tableLoading && displayItems.length === 0"
          :image-size="80"
          :description="isRejectedTab ? '当前筛选下没有被驳回的填报（按前 100 条任务近似匹配）' : '本单元暂未收到下发的任务'"
        />
        <div v-for="row in displayItems" :key="`card-${row.id}`" class="task-card">
          <div class="task-card-header">
            <span class="mono task-card-code">{{ row.code }}</span>
            <StatusTag v-if="row.yearStatus" kind="TaskYearStatus" :value="row.yearStatus" />
            <StatusTag v-else kind="TaskStatus" :value="row.status" />
            <el-tag v-if="row.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
          </div>
          <div class="task-card-name">{{ row.indicator?.name ?? '—' }}</div>
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
          <div class="task-card-year-rate">本年完成率 {{ formatPercent(row.completionRate ?? null) }}</div>
          <div class="task-card-actions">
            <el-button v-if="canSubmitRow(row)" type="primary" size="small" @click="goSubmit(row)">填报</el-button>
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canApplyRow(row)" type="warning" size="small" plain @click="goApply(row)">调整</el-button>
            <el-button
              v-if="canAcceptRow(row)"
              type="primary"
              size="small"
              :loading="acceptingId === row.id"
              @click="handleAccept(row)"
            >
              确认接收
            </el-button>
          </div>
        </div>
      </div>

      <div class="task-table">
        <el-table v-loading="tableLoading" :data="displayItems" border size="small">
        <el-table-column label="任务编号" width="160" fixed="left" class-name="mono">
          <template #default="{ row }">
            {{ row.code }}
            <el-tag v-if="row.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关键产出指标" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.indicator?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="所属 A / A+" width="130">
          <template #default="{ row }">
            <span class="mono">{{ indicatorLine(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="口径" width="76" align="center">
          <template #default="{ row }">
            <StatusTag v-if="row.indicator?.statCaliber" kind="StatCaliber" :value="row.indicator.statCaliber" />
            <template v-else>—</template>
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
        <el-table-column label="本年完成率" width="96" align="right">
          <template #default="{ row }">
            {{ formatPercent(row.completionRate ?? null) }}
          </template>
        </el-table-column>
        <el-table-column label="年度状态" width="96" align="center">
          <template #default="{ row }">
            <StatusTag v-if="row.yearStatus" kind="TaskYearStatus" :value="row.yearStatus" />
            <span v-else class="empty-text">—</span>
          </template>
        </el-table-column>
        <el-table-column label="任务状态" width="96" align="center">
          <template #default="{ row }">
            <StatusTag kind="TaskStatus" :value="row.status" />
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
        <el-table-column label="操作" width="190" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="canSubmitRow(row)" link type="primary" @click="goSubmit(row)">填报</el-button>
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canApplyRow(row)" link type="warning" @click="goApply(row)">调整</el-button>
            <el-button
              v-if="canAcceptRow(row)"
              type="primary"
              size="small"
              :loading="acceptingId === row.id"
              @click="handleAccept(row)"
            >
              确认接收
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :image-size="80"
            :description="isRejectedTab ? '当前筛选下没有被驳回的填报（按前 100 条任务近似匹配）' : '本单元暂未收到下发的任务'"
          />
        </template>
      </el-table>
      </div>

      <div v-if="!isRejectedTab" class="pager">
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
      @submit="({ taskId, year }) => router.push({ path: `/tasks/${taskId}/submit`, query: year ? { year } : undefined })"
      @apply="({ taskId }) => router.push({ path: '/adjustments/apply', query: { taskId } })"
    />
  </div>
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
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
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

.filter-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.mono {
  font-family: var(--app-font-mono);
}

.rate-text {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
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
  margin-top: var(--space-4);
}

/* 移动端任务卡片：默认隐藏，仅 ≤768px 显示 */
.task-cards {
  display: none;
}

/* 移动端（≤768px，UI 规范 5.2）：KPI 两列三行、表格改卡片单列堆叠 */
@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
    gap: var(--space-2);
  }

  .kpi-value {
    font-size: 18px;
    line-height: 26px;
  }

  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
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
    justify-content: flex-end;
    gap: var(--space-2);
    margin-top: var(--space-2);
  }

  .task-card-year-rate {
    margin-top: var(--space-1);
    font-size: 12px;
    color: var(--el-text-color-secondary);
    font-variant-numeric: tabular-nums;
  }

  .task-card-actions .el-button {
    min-height: 32px;
  }

  .pager {
    justify-content: center;
  }

  .pager :deep(.el-pagination__sizes),
  .pager :deep(.el-pagination__jump) {
    display: none;
  }
}
</style>
