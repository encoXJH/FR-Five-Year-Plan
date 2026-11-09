<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import PageState from '@/components/PageState.vue'
import TaskDetailSheet from '@/components/TaskDetailSheet.vue'
import { listAllTasks, listTasks } from '@/api/task'
import { getStatsOverview } from '@/api/stats'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { useUserStore } from '@/stores/user'
import { formatCount, formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import {
  averageTaskYearCompletion,
  canWriteTask,
  formatDeadline,
  primaryTaskIndicator,
  taskMaterialState,
} from '@/utils/task'
import { canApplyForTask } from '@/utils/adjustment'
import type { PageResult } from '@/types/api'
import type { TaskListItem, TaskQuery } from '@/types/task'
import type { TaskYearStatus } from '@/types/enums'

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const currentYear = new Date().getFullYear()

const isUnitOwner = computed(() => userStore.roles.includes('UNIT_OWNER'))
const isAdminView = computed(() => !isUnitOwner.value && userStore.roles.includes('ADMIN'))
const unitOptions = ref<OrgUnit[]>([])
const unitPickerVisible = ref(false)
const yearPickerVisible = ref(false)

const filters = reactive({
  unitId: '',
  year: currentYear as number | null,
  tab: 'all',
})

const yearOptions = computed(() => {
  const list: number[] = []
  for (let year = currentYear - 1; year <= currentYear + 4; year += 1) list.push(year)
  return list
})

const STATUS_TABS = [
  { key: 'all', label: '全部', query: {} },
  { key: 'todo', label: '待填报', query: { yearStatus: 'IN_PROGRESS' } },
  { key: 'review', label: '待审核', query: { yearStatus: 'PENDING_REVIEW' } },
] as const

const loading = ref(false)
const page = ref<PageResult<TaskListItem>>({ items: [], total: 0, page: 1, pageSize: 20 })
const kpi = reactive({
  all: 0,
  yearRate: null as number | null,
  todo: 0,
  review: 0,
})
const warningTaskIds = ref<ReadonlySet<string>>(new Set())

const unitName = computed(() => {
  if (isUnitOwner.value) return userStore.userInfo?.unit?.name ?? '本单元'
  return unitOptions.value.find((unit) => unit.id === filters.unitId)?.name ?? '全部单元'
})
const unitLabel = computed(() => {
  const unit = unitOptions.value.find((item) => item.id === filters.unitId)
  return unit ? `${unit.code} ${unit.name}` : '全部单元'
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
    kpi.all = batch.length
    kpi.yearRate = averageTaskYearCompletion(batch)
    await loadWarnings(batch)
  } catch {
    // KPI 失败不阻塞列表
  }
}

function applyFilters(): void {
  page.value.page = 1
  void loadTasks()
  void loadKpi()
}

function switchTab(key: string): void {
  if (filters.tab === key) return
  filters.tab = key
  page.value.page = 1
  void loadTasks()
}

function statusCount(key: string): number {
  if (key === 'todo') return kpi.todo
  if (key === 'review') return kpi.review
  return kpi.all
}

function loadMore(): void {
  page.value.page += 1
  void listTasks(currentQuery({ page: page.value.page, pageSize: page.value.pageSize })).then((result) => {
    page.value = {
      ...result,
      items: [...page.value.items, ...result.items],
    }
  })
}

const hasMore = computed(() => page.value.items.length < page.value.total)

// 操作
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
    if (!canWriteTask(row)) showToast('历史任务只读，不能填报')
    return
  }
  router.push({
    path: `/tasks/${row.id}/submit`,
    query: filters.year ? { year: filters.year } : undefined,
  })
}

function canApplyRow(row: TaskListItem): boolean {
  return canWriteTask(row)
    && userStore.can('adjustment:apply')
    && canApplyForTask(row.status, primaryTaskIndicator(row)?.statCaliber, row.isHistorical)
}

function goApply(row: TaskListItem): void {
  if (!canApplyRow(row)) {
    if (!canWriteTask(row)) showToast('历史任务只读，不能发起调整')
    return
  }
  router.push({ path: '/adjustments/apply', query: { taskId: row.id } })
}

function materialState(row: TaskListItem) {
  return taskMaterialState(row)
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
  void loadTasks()
  void loadKpi()
  if (isAdminView.value) {
    listOrgUnits().then((list) => {
      unitOptions.value = list
    }).catch(() => undefined)
  }
})
</script>

<template>
  <div class="page page--tabbar">
    <div class="workbench-head">
      <p class="muted small">{{ isAdminView ? '管理视角' : '任务执行' }}</p>
      <h1>{{ isAdminView ? '任务代填' : `${unitName} · 学院工作台` }}</h1>
      <p class="muted small">{{ filters.year ? `${filters.year} 年` : '全部年度' }} · 填报、佐证材料与完成进度统一处理</p>
    </div>

    <div v-if="isAdminView && !filters.unitId" class="info-box">任务代填模式：请先选择执行单元</div>

    <div class="filter-line">
      <button v-if="isAdminView" type="button" class="filter-btn" @click="unitPickerVisible = true">
        {{ unitLabel }}
        <van-icon name="arrow-down" size="11" />
      </button>
      <button type="button" class="filter-btn" @click="yearPickerVisible = true">
        {{ filters.year ? `${filters.year} 年` : '全部年度' }}
        <van-icon name="arrow-down" size="11" />
      </button>
      <van-button size="small" type="primary" @click="applyFilters">查询</van-button>
    </div>

    <div class="filter-scroll">
      <button
        v-for="tab in STATUS_TABS"
        :key="tab.key"
        type="button"
        class="filter-chip"
        :class="{ 'is-active': filters.tab === tab.key }"
        @click="switchTab(tab.key)"
      >{{ tab.label }} {{ statusCount(tab.key) }}</button>
    </div>

    <div class="summary-row">
      <div class="summary-card"><span>待填报</span><strong>{{ kpi.todo }}</strong></div>
      <div class="summary-card"><span>待审核</span><strong>{{ kpi.review }}</strong></div>
      <div class="summary-card"><span>本年完成率</span><strong>{{ formatPercent(kpi.yearRate) }}</strong></div>
    </div>

    <PageState
      :loading="loading && page.items.length === 0"
      :empty="page.items.length === 0"
      empty-text="当前筛选下暂无任务"
    >
      <div class="task-list">
        <article v-for="row in page.items" :key="row.id" class="task-card">
          <div class="row--between">
            <div class="row">
              <span class="task-code">{{ row.code }}</span>
              <span v-if="row.isHistorical" class="chip chip--gray">历史只读</span>
            </div>
            <StatusTag
              v-if="row.status === 'PENDING_DISTRIBUTION'"
              kind="TaskStatus"
              :value="row.status"
            />
            <StatusTag v-else-if="row.yearStatus" kind="TaskYearStatus" :value="row.yearStatus" />
            <StatusTag v-else kind="TaskStatus" :value="row.status" />
          </div>

          <h2 class="task-name">{{ taskName(row) }}</h2>
          <div class="task-sub">{{ indicatorLine(row) }} · {{ row.unit?.name ?? '—' }}</div>

          <div class="task-numbers">
            <div><span>五年目标</span><strong>{{ formatTargetValue(row.totalTarget ?? null, caliberOf(row)) }}</strong></div>
            <div><span>累计完成</span><strong>{{ formatTargetValue(row.cumulativeActual ?? null, caliberOf(row)) }}</strong></div>
            <div><span>本年目标</span><strong>{{ formatTargetValue(row.yearTarget ?? null, caliberOf(row)) }}</strong></div>
            <div><span>本年完成</span><strong>{{ formatTargetValue(row.actualValue ?? null, caliberOf(row)) }}</strong></div>
          </div>

          <div class="task-progress">
            <div class="row--between">
              <span class="small muted">五年累计完成率</span>
              <span class="small" :class="warningTaskIds.has(row.id) ? 'text-danger' : ''">
                {{ formatPercent(row.cumulativeRate) }}
                <template v-if="warningTaskIds.has(row.id)"> · 滞后预警</template>
              </span>
            </div>
            <ProgressBar :rate="row.cumulativeRate" :height="8" />
          </div>

          <div class="task-deadline" :class="{ 'is-urgent': formatDeadline(row.deadline).urgent }">
            截止 {{ formatDeadline(row.deadline).text }}
          </div>

          <div class="material-line" :class="{ 'is-missing': materialState(row).missingCount > 0 }">
            <div class="row">
              <span class="small">佐证材料</span>
              <span class="chip" :class="`chip--${materialState(row).tagType === 'success' ? 'success' : materialState(row).tagType === 'warning' ? 'warning' : 'gray'}`">
                {{ materialState(row).label }}
              </span>
              <span v-if="materialState(row).missingCount > 0" class="small text-danger">
                缺 {{ materialState(row).missingCount }} 份
              </span>
            </div>
            <button type="button" class="text-btn" @click="openMaterials(row)">
              预览 · {{ formatCount(materialState(row).fileCount) }} 份
            </button>
          </div>

          <div class="task-actions">
            <van-button v-if="canSubmitRow(row)" size="small" type="primary" @click="goSubmit(row)">
              {{ materialState(row).missingCount > 0 ? '去补材料' : '填报' }}
            </van-button>
            <van-button size="small" @click="openDetail(row)">详情</van-button>
            <van-button v-if="canApplyRow(row)" size="small" type="warning" plain @click="goApply(row)">调整</van-button>
          </div>
        </article>
      </div>
      <div v-if="hasMore" class="load-more" @click="loadMore">加载更多（{{ page.items.length }} / {{ page.total }}）</div>
      <div v-else-if="page.items.length > 0" class="load-more">已加载全部 {{ page.total }} 条</div>
    </PageState>

    <TaskDetailSheet
      v-model="drawerVisible"
      :task-id="activeTaskId"
      :initial-year="drawerInitialYear"
      :initial-submission-id="drawerInitialSubmissionId"
      :initial-tab="drawerInitialTab"
    />

    <van-popup v-model:show="unitPickerVisible" position="bottom" round :style="{ height: '65vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择执行单元</strong>
          <button type="button" class="close-btn" @click="unitPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="filters.unitId = ''; unitPickerVisible = false; applyFilters()">
            全部单元
          </button>
          <button
            v-for="unit in unitOptions"
            :key="unit.id"
            type="button"
            class="picker-item"
            @click="filters.unitId = unit.id; unitPickerVisible = false; applyFilters()"
          >{{ unit.code }} {{ unit.name }}</button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="yearPickerVisible" position="bottom" round>
      <van-picker
        :columns="[{ text: '全部年度', value: 0 }, ...yearOptions.map((year) => ({ text: `${year} 年`, value: year }))]"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: number }> }) => {
          filters.year = value.selectedOptions[0]?.value || null
          yearPickerVisible = false
          applyFilters()
        }"
        @cancel="yearPickerVisible = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.workbench-head {
  margin: 2px 0 10px;
}
.workbench-head h1 {
  margin: 2px 0;
  font-size: 21px;
}
.workbench-head p {
  margin: 0;
}
.info-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #eef3fc;
  color: #4a5f85;
  font-size: 12px;
}
.filter-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.filter-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: #fff;
  color: var(--text);
  font-size: 12px;
}
.summary-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 10px;
}
.summary-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px;
  border-radius: 10px;
  background: #fff;
  box-shadow: var(--shadow);
}
.summary-card span {
  color: var(--text-secondary);
  font-size: 11px;
}
.summary-card strong {
  color: var(--brand);
  font-size: 18px;
}
.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.task-card {
  padding: 12px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.task-code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
}
.task-name {
  margin: 8px 0 2px;
  font-size: 15px;
  line-height: 21px;
}
.task-sub {
  color: var(--text-secondary);
  font-size: 12px;
}
.task-numbers {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin: 8px 0;
}
.task-numbers > div {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 6px 8px;
  border-radius: 8px;
  background: #f8fafd;
}
.task-numbers span {
  color: var(--text-secondary);
  font-size: 10px;
}
.task-numbers strong {
  font-size: 13px;
}
.task-progress {
  margin: 6px 0;
}
.task-deadline {
  color: var(--text-secondary);
  font-size: 11px;
}
.task-deadline.is-urgent {
  color: var(--warning);
}
.material-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
  padding: 8px;
  border-radius: 8px;
  background: #f8fafd;
}
.material-line.is-missing {
  background: #fdf7f5;
}
.text-btn {
  min-height: 32px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 12px;
}
.task-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--line);
}
.picker-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 14px calc(14px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 16px;
}
.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text-secondary);
}
.picker-list {
  flex: 1;
  overflow-y: auto;
}
.picker-item {
  width: 100%;
  min-height: 48px;
  padding: 10px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
</style>
