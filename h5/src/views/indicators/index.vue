<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import PageState from '@/components/PageState.vue'
import PlanSwitcher from '@/components/PlanSwitcher.vue'
import TaskDetailSheet from '@/components/TaskDetailSheet.vue'
import AttachmentList from '@/components/AttachmentList.vue'
import IndicatorFormSheet from './components/IndicatorFormSheet.vue'
import ImportSheet from './components/ImportSheet.vue'
import { getPlan, listPlans } from '@/api/plan'
import { createDirection, deleteDirection, listDirections } from '@/api/direction'
import { deleteIndicator, getIndicatorBudget, listIndicators, putIndicatorBudget } from '@/api/indicator'
import { listAllTasks } from '@/api/task'
import { createExport, pollExport } from '@/api/export'
import { getIndicatorTrend, getStatsTree } from '@/api/stats'
import { countMaterialsByIndicator, listMaterials } from '@/api/material'
import { getUserOptions } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { enumLabel } from '@/utils/enumLabels'
import { newIdempotencyKey } from '@/utils/idempotency'
import {
  booleanPlanYear,
  completionLevel,
  completionPercent,
  formatActualValue,
  formatTargetValue,
  hasQuantitativeFields,
  indexStatsTree,
  yearTargetMap,
  type StatsTreeIndex,
} from '@/utils/indicator'
import { formatCount, formatDateTime, formatMoney, formatPercent } from '@/utils/format'
import { triggerFileDownload } from '@/utils/file'
import type { Direction, PlanCard, PlanDetail } from '@/types/plan'
import type { Indicator, IndicatorBudgetView, IndicatorTreeNode } from '@/types/indicator'
import type { FileView } from '@/types/file'
import type { IndicatorTrend, StatsTreeNode } from '@/types/stats'
import type { IndicatorNature, StatCaliber } from '@/types/enums'
import type { TaskListItem } from '@/types/task'
import type { UserOption } from '@/types/user'
import type { MaterialItem } from '@/api/material'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const planId = computed(() => (route.params.planId as string) || (route.query.planId as string) || '')
const loading = ref(false)
const plan = ref<PlanDetail | null>(null)
const plans = ref<PlanCard[]>([])
const tree = ref<IndicatorTreeNode[]>([])
const directionList = ref<Direction[]>([])
const userOptions = ref<UserOption[]>([])
const yearStatsIndexes = ref<Map<number, StatsTreeIndex>>(new Map())
const materialCounts = ref<Record<string, number>>({})
const focusStats = ref<StatsTreeNode[]>([])

let planRequestId = 0
let treeRequestId = 0
let statsRequestId = 0
let directionRequestId = 0
let materialCountRequestId = 0

const canEdit = computed(() => userStore.can('indicator:edit'))
const canAllocate = computed(() => userStore.can('allocation:edit'))
const canBudget = computed(() => userStore.can('indicator:budget'))
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))
const isArchived = computed(() => plan.value?.status === 'ARCHIVED')
const readOnly = computed(() => isArchived.value)

const years = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) list.push(year)
  return list
})
const currentYear = new Date().getFullYear()
const focusYear = computed(() => (years.value.includes(currentYear) ? currentYear : (years.value[0] ?? currentYear)))

const defaultOnlyMine = (): boolean => userStore.roles.includes('SCHOOL_OWNER')
const filters = reactive({
  nature: '' as IndicatorNature | '',
  ownerId: '',
  quantStatus: '' as '' | 'QUANTIFIABLE' | 'NON_QUANTIFIABLE',
  completionStatus: '' as '' | 'UNFINISHED' | 'FINISHED',
  onlyMine: defaultOnlyMine(),
  keyword: '',
})
const selectedDirectionId = ref('')

async function loadPlans(): Promise<void> {
  try {
    plans.value = (await listPlans()).filter((item) => item.planType !== 'TRAINING')
    if (!planId.value) {
      const active = plans.value.find((item) => item.status === 'ACTIVE') ?? plans.value[0]
      if (active) await router.replace(`/plans/${active.id}/indicators`)
    }
  } catch {
    // 拦截器已提示
  }
}

async function loadDirections(): Promise<void> {
  const requestedPlanId = planId.value
  const requestId = ++directionRequestId
  if (!requestedPlanId || !isAdmin.value) {
    directionList.value = []
    return
  }
  try {
    const result = await listDirections(requestedPlanId)
    if (requestId === directionRequestId && requestedPlanId === planId.value) directionList.value = result
  } catch {
    if (requestId === directionRequestId) directionList.value = []
  }
}

async function loadStatsTree(): Promise<void> {
  const requestedPlanId = planId.value
  if (!requestedPlanId) return
  const requestId = ++statsRequestId
  const planYears = years.value.length > 0 ? [...years.value] : [currentYear]
  try {
    const trees = await Promise.all(planYears.map((year) => getStatsTree(requestedPlanId, year)))
    if (requestId !== statsRequestId || requestedPlanId !== planId.value) return
    const indexes = new Map<number, StatsTreeIndex>()
    planYears.forEach((year, index) => indexes.set(year, indexStatsTree(trees[index] ?? [])))
    yearStatsIndexes.value = indexes
    forumUpdateFocusTree(trees, planYears)
  } catch {
    if (requestId === statsRequestId) yearStatsIndexes.value = new Map()
  }
}

function forumUpdateFocusTree(trees: StatsTreeNode[][], planYears: number[]): void {
  const index = Math.max(0, planYears.indexOf(focusYear.value))
  focusStats.value = trees[index] ?? []
}

async function loadMaterialCounts(): Promise<void> {
  const requestedPlanId = planId.value
  if (!requestedPlanId) return
  const requestId = ++materialCountRequestId
  try {
    const result = await countMaterialsByIndicator(requestedPlanId)
    if (requestId !== materialCountRequestId || requestedPlanId !== planId.value) return
    materialCounts.value = Object.fromEntries(result.map((item) => [item.indicatorId, item.materialCount]))
  } catch {
    if (requestId === materialCountRequestId) materialCounts.value = {}
  }
}

async function loadTree(): Promise<void> {
  const requestedPlanId = planId.value
  if (!requestedPlanId) return
  const requestId = ++treeRequestId
  const ownerId = filters.onlyMine ? (userStore.userInfo?.id ?? '') : filters.ownerId
  const result = await listIndicators(requestedPlanId, {
    view: 'tree',
    ownerId: ownerId || undefined,
    keyword: filters.keyword || undefined,
  })
  if (requestId !== treeRequestId || requestedPlanId !== planId.value) return
  tree.value = result
  await restoreExpanded()
}

async function loadAll(): Promise<void> {
  const requestedPlanId = planId.value
  if (!requestedPlanId) return
  const requestId = ++planRequestId
  loading.value = true
  try {
    const detail = await getPlan(requestedPlanId)
    if (requestId !== planRequestId || requestedPlanId !== planId.value) return
    plan.value = detail
    await Promise.all([loadTree(), loadStatsTree(), loadDirections(), loadMaterialCounts()])
  } finally {
    if (requestId === planRequestId) loading.value = false
  }
}

function applyFilters(): void {
  void loadTree()
}

const directionOptions = computed(() => {
  const options = new Map<string, { id: string; name: string; count: number }>()
  for (const direction of directionList.value) {
    options.set(direction.id, { id: direction.id, name: direction.name, count: 0 })
  }
  for (const indicator of tree.value) {
    if (indicator.directionId && !options.has(indicator.directionId)) {
      options.set(indicator.directionId, {
        id: indicator.directionId,
        name: indicator.directionName ?? '未命名建设方向',
        count: 0,
      })
    }
  }
  for (const item of options.values()) {
    item.count = tree.value.filter((indicator) => indicator.directionId === item.id).length
  }
  const unassigned = tree.value.filter((indicator) => !indicator.directionId).length
  const result = [...options.values()]
  if (unassigned > 0) result.push({ id: '__UNASSIGNED__', name: '未分配建设方向', count: unassigned })
  return result
})

const visibleTree = computed(() => {
  let nodes = tree.value
  if (selectedDirectionId.value === '__UNASSIGNED__') {
    nodes = nodes.filter((node) => !node.directionId)
  } else if (selectedDirectionId.value) {
    nodes = nodes.filter((node) => node.directionId === selectedDirectionId.value)
  }
  if (filters.quantStatus === 'QUANTIFIABLE') nodes = nodes.filter((node) => node.isQuantifiable)
  if (filters.quantStatus === 'NON_QUANTIFIABLE') nodes = nodes.filter((node) => !node.isQuantifiable)
  if (filters.completionStatus) {
    const year = currentYear
    nodes = nodes.filter((node) => {
      if (!node.isQuantifiable || !hasTargetValue(targetOf(node, year))) return false
      const stats = aStatsOfYear(node.id, year)
      const achieved = stats?.achieveStatus === 'ACHIEVED' || stats?.achieveStatus === 'EXCEEDED'
      return filters.completionStatus === 'FINISHED' ? achieved : !achieved
    })
  }
  if (filters.nature) nodes = nodes.filter((node) => node.nature === filters.nature)
  return nodes
})

const aRows = computed(() => tree.value.filter((node) => node.level === 'A'))
const constraintCount = computed(() => aRows.value.filter((node) => node.nature === 'CONSTRAINT').length)
const developmentCount = computed(() => aRows.value.filter((node) => node.nature === 'DEVELOPMENT').length)

const targetMaps = computed(() => {
  const maps = new Map<string, Map<number, number | boolean | null>>()
  const walk = (nodes: IndicatorTreeNode[]): void => {
    for (const node of nodes) {
      maps.set(node.id, yearTargetMap(node.yearTargets))
      if (node.children.length) walk(node.children)
    }
  }
  walk(tree.value)
  return maps
})

function targetOf(row: Indicator, year: number): number | boolean | null {
  if (!hasQuantitativeFields(row.level, row.isQuantifiable)) return null
  return targetMaps.value.get(row.id)?.get(year) ?? null
}

function totalTargetDisplay(row: Indicator): string {
  if (!hasQuantitativeFields(row.level, row.isQuantifiable)) return '—'
  if (row.statCaliber === 'BOOLEAN') {
    const planYear = booleanPlanYear(row.yearTargets)
    return planYear === null ? '—' : `是（${planYear} 年）`
  }
  return formatTargetValue(row.totalTarget, row.statCaliber)
}

function aStatsOfYear(indicatorId: string, year: number): StatsTreeNode | undefined {
  return yearStatsIndexes.value.get(year)?.indicators.get(indicatorId)
}

function aStats(indicatorId: string): StatsTreeNode | undefined {
  return focusStats.value.length > 0
    ? yearStatsIndexes.value.get(focusYear.value)?.indicators.get(indicatorId)
    : undefined
}

function hasTargetValue(target: number | boolean | null | undefined): boolean {
  return typeof target === 'boolean' ? target : typeof target === 'number' && target > 0
}

function aTotalPercent(indicator: Indicator): number {
  const stats = aStats(indicator.id)
  return completionPercent(stats?.cumulativeRate, stats?.cumulativeActual, indicator.totalTarget)
}

function aTotalText(indicator: Indicator): string {
  const stats = aStats(indicator.id)
  if (typeof stats?.cumulativeRate === 'number') return formatPercent(stats.cumulativeRate)
  const percent = aTotalPercent(indicator)
  return hasTargetValue(indicator.totalTarget) || percent > 0 ? `${percent.toFixed(1)}%` : '—'
}

function yearCompletionPercent(indicator: Indicator, year: number): number {
  const stats = aStatsOfYear(indicator.id, year)
  return completionPercent(stats?.completionRate, stats?.actualValue, targetOf(indicator, year))
}

function yearActualValue(indicator: Indicator, year: number): number | null | undefined {
  const stats = aStatsOfYear(indicator.id, year)
  return indicator.statCaliber === 'GROWTH' ? stats?.actualTotal : stats?.actualValue
}

function yearActualDisplay(indicator: Indicator, year: number): string {
  return formatActualValue(yearActualValue(indicator, year), indicator.statCaliber)
}

function materialCountOf(indicatorId: string): number {
  return materialCounts.value[indicatorId] ?? 0
}

function canEditRow(row: IndicatorTreeNode): boolean {
  return canEdit.value
    && !readOnly.value
    && row.status !== 'DISTRIBUTED'
    && !row.children.some((child) => child.status === 'DISTRIBUTED')
}

function canAllocateRow(row: IndicatorTreeNode): boolean {
  return canAllocate.value && row.level === 'A' && row.isQuantifiable && !readOnly.value
}

// ---------- 展开与任务 ----------
const expandedIds = reactive(new Set<string>())
const tasksByIndicator = reactive<Record<string, TaskListItem[]>>({})
const taskLoadingIds = reactive(new Set<string>())
const taskErrorIds = reactive(new Set<string>())

function expansionStorageKey(): string {
  return `fzsu.h5.indicatorExpanded.${planId.value}`
}

async function restoreExpanded(): Promise<void> {
  expandedIds.clear()
  if (!planId.value) return
  try {
    const raw = localStorage.getItem(expansionStorageKey())
    if (!raw) return
    const ids: unknown = JSON.parse(raw)
    if (!Array.isArray(ids)) return
    const valid = ids.filter((id): id is string => typeof id === 'string' && tree.value.some((node) => node.id === id))
    if (valid.length > 20) return
    valid.forEach((id) => expandedIds.add(id))
    for (const id of valid) void ensureIndicatorTasks(id).catch(() => undefined)
  } catch {
    // 忽略
  }
}

function persistExpanded(): void {
  try {
    localStorage.setItem(expansionStorageKey(), JSON.stringify([...expandedIds]))
  } catch {
    // 忽略
  }
}

async function ensureIndicatorTasks(indicatorId: string): Promise<TaskListItem[]> {
  if (Object.prototype.hasOwnProperty.call(tasksByIndicator, indicatorId)) return tasksByIndicator[indicatorId]
  taskLoadingIds.add(indicatorId)
  taskErrorIds.delete(indicatorId)
  try {
    const tasks = await listAllTasks({ indicatorId })
    tasksByIndicator[indicatorId] = tasks
    return tasks
  } catch (error) {
    taskErrorIds.add(indicatorId)
    throw error
  } finally {
    taskLoadingIds.delete(indicatorId)
  }
}

async function toggleExpand(row: IndicatorTreeNode): Promise<void> {
  if (expandedIds.has(row.id)) {
    expandedIds.delete(row.id)
  } else {
    if (!row.isQuantifiable) return
    try {
      await ensureIndicatorTasks(row.id)
    } catch {
      return
    }
    expandedIds.add(row.id)
  }
  persistExpanded()
}

// ---------- 操作 ----------
const formVisible = ref(false)
const editingIndicator = ref<Indicator | null>(null)

function openCreate(): void {
  editingIndicator.value = null
  formVisible.value = true
}

function openEdit(row: Indicator): void {
  editingIndicator.value = row
  formVisible.value = true
}

const rowActionVisible = ref(false)
const rowActionTarget = ref<IndicatorTreeNode | null>(null)
const rowActionItems = computed(() => {
  if (!rowActionTarget.value) return []
  return [
    { name: '编辑指标', key: 'edit' },
    { name: '删除指标', key: 'delete', color: '#E45B5B' },
  ]
})

function openRowActions(row: IndicatorTreeNode): void {
  rowActionTarget.value = row
  rowActionVisible.value = true
}

async function handleRowAction(action: { key: string }): Promise<void> {
  const row = rowActionTarget.value
  rowActionVisible.value = false
  rowActionTarget.value = null
  if (!row) return
  if (action.key === 'edit') openEdit(row)
  else if (action.key === 'delete') await handleDelete(row)
}

async function onSaved(): Promise<void> {
  await Promise.all([loadTree(), loadStatsTree(), loadMaterialCounts()])
}

async function handleDelete(row: Indicator): Promise<void> {
  try {
    await showConfirmDialog({
      title: '删除指标',
      message: `确认删除指标「${row.code} ${row.name}」？已下发的指标不能删除（40004）。`,
      confirmButtonText: '确认删除',
    })
  } catch {
    return
  }
  await deleteIndicator(row.id)
  showToast('指标已删除')
  await loadTree()
}

function goDetail(row: Indicator): void {
  router.push(`/indicators/${row.id}`)
}

function goAllocation(row: IndicatorTreeNode): void {
  router.push({ path: `/plans/${planId.value}/allocation`, query: { indicatorId: row.id } })
}

function goProgress(): void {
  router.push(`/plans/${planId.value}/indicator-progress`)
}

function openTaskMaterials(task: TaskListItem, year: number): void {
  const indicatorId = task.topIndicator?.id ?? task.indicator?.id
  if (!indicatorId) return
  router.push({
    path: '/materials',
    query: {
      planId: planId.value,
      indicatorId,
      ...(task.unit?.id ? { unitId: task.unit.id } : {}),
      taskId: task.id,
      taskCode: task.code,
      year: String(year),
    },
  })
}

const taskSheetVisible = ref(false)
const taskSheetId = ref<string | null>(null)
function openTaskDetail(task: TaskListItem): void {
  taskSheetId.value = task.id
  taskSheetVisible.value = true
}

// ---------- 方向新增/删除 ----------
const directionVisible = ref(false)
const directionName = ref('')
const savingDirection = ref(false)

function openAddDirection(): void {
  directionName.value = ''
  directionVisible.value = true
}

async function saveDirection(): Promise<void> {
  if (!directionName.value.trim()) {
    showToast('请输入方向名称')
    return
  }
  savingDirection.value = true
  try {
    const nextSort = Math.max(0, ...directionList.value.map((item) => item.sort ?? 0)) + 1
    await createDirection(planId.value, { name: directionName.value.trim(), sort: nextSort, leaderId: null })
    showToast('主要建设方向已添加')
    directionVisible.value = false
    await loadDirections()
  } finally {
    savingDirection.value = false
  }
}

async function handleDeleteDirection(direction: { id: string; name: string }): Promise<void> {
  try {
    await showConfirmDialog({
      title: '删除建设方向',
      message: `确认删除「${direction.name}」？该方向下存在指标时不能删除（40008）。`,
      confirmButtonText: '确认删除',
    })
  } catch {
    return
  }
  await deleteDirection(direction.id)
  showToast('主要建设方向已删除')
  if (selectedDirectionId.value === direction.id) selectedDirectionId.value = ''
  await Promise.all([loadDirections(), loadTree()])
}

// ---------- 导入 / 导出 ----------
const importVisible = ref(false)
const exporting = ref(false)

async function handleExport(): Promise<void> {
  exporting.value = true
  try {
    const { exportId } = await createExport(
      { type: 'INDICATORS', params: { planId: planId.value } },
      newIdempotencyKey(),
    )
    const job = await pollExport(exportId)
    if (job.status === 'SUCCEEDED' && job.downloadUrl) {
      triggerFileDownload(job.downloadUrl, '指标总表.xlsx')
      showToast('导出完成，已开始下载')
    } else {
      showToast(job.errorMessage ?? '导出任务失败')
    }
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

// ---------- 五年进度 ----------
const progressVisible = ref(false)
const progressLoading = ref(false)
const progressIndicator = ref<IndicatorTreeNode | null>(null)
const progressTrend = ref<IndicatorTrend | null>(null)
let progressRequestId = 0

async function openProgress(row: IndicatorTreeNode): Promise<void> {
  progressIndicator.value = row
  progressTrend.value = null
  progressVisible.value = true
  progressLoading.value = true
  const requestId = ++progressRequestId
  try {
    const result = await getIndicatorTrend(row.id)
    if (requestId === progressRequestId) progressTrend.value = result
  } finally {
    if (requestId === progressRequestId) progressLoading.value = false
  }
}

const progressSummary = computed(() => {
  const stats = progressIndicator.value ? aStats(progressIndicator.value.id) : undefined
  const children = stats?.children ?? []
  return {
    rate: progressTrend.value?.cumulativeRate ?? stats?.cumulativeRate ?? null,
    requiredCount: children.length,
    achievedCount: children.filter((item) => item.isAchieved === true || item.yearStatus === 'ACHIEVED' || item.yearStatus === 'MADE_UP').length,
    inProgressCount: children.filter((item) => item.yearStatus === 'IN_PROGRESS' || item.yearStatus === 'PENDING_REVIEW' || item.yearStatus === 'ADJUSTING').length,
  }
})

// ---------- 材料 ----------
const materialVisible = ref(false)
const materialLoading = ref(false)
const materialIndicator = ref<IndicatorTreeNode | null>(null)
const materialRows = ref<MaterialItem[]>([])
const materialTotal = ref(0)
const materialPage = ref(1)
const materialPageSize = 10
let materialRequestId = 0

async function openMaterials(row: IndicatorTreeNode): Promise<void> {
  materialIndicator.value = row
  materialRows.value = []
  materialTotal.value = 0
  materialPage.value = 1
  materialVisible.value = true
  await loadMaterials()
}

async function loadMaterials(): Promise<void> {
  const indicator = materialIndicator.value
  if (!indicator) return
  const requestId = ++materialRequestId
  materialLoading.value = true
  try {
    const result = await listMaterials({
      indicatorId: indicator.id,
      page: materialPage.value,
      pageSize: materialPageSize,
    })
    if (requestId !== materialRequestId) return
    materialRows.value = result.items
    materialTotal.value = result.total
  } finally {
    if (requestId === materialRequestId) materialLoading.value = false
  }
}

function changeMaterialPage(page: number): void {
  materialPage.value = page
  void loadMaterials()
}

// ---------- 指标预算 ----------
const budgetVisible = ref(false)
const budgetLoading = ref(false)
const budgetIndicator = ref<IndicatorTreeNode | null>(null)
const budgetView = ref<IndicatorBudgetView | null>(null)
const budgetYear = ref<number | null>(null)
const budgetPage = ref(1)
const budgetPageSize = 10
const budgetAmount = ref('')
const budgetReason = ref('')
const savingBudget = ref(false)
let budgetRequestId = 0

function budgetCardText(row: Indicator): string {
  if (row.totalBudget == null) return '未设置'
  return `${formatMoney(row.totalBudget)} 万 · 已花 ${formatMoney(row.spentBudget ?? 0)} 万`
}

function asAttachments(files: FileView[]) {
  return files.map((file) => ({ ...file, transcodeStatus: null }))
}

const budgetRemainingText = computed(() => {
  const view = budgetView.value
  if (!view || view.totalBudget == null) return '—'
  return `${formatMoney(view.totalBudget - (view.spentBudget ?? 0))} 万`
})

async function openBudget(row: IndicatorTreeNode): Promise<void> {
  budgetIndicator.value = row
  budgetView.value = null
  budgetYear.value = null
  budgetPage.value = 1
  budgetAmount.value = row.totalBudget == null ? '' : String(row.totalBudget)
  budgetReason.value = ''
  budgetVisible.value = true
  await loadBudget()
}

async function loadBudget(): Promise<void> {
  const indicator = budgetIndicator.value
  if (!indicator) return
  const requestId = ++budgetRequestId
  budgetLoading.value = true
  try {
    const result = await getIndicatorBudget(indicator.id, {
      page: budgetPage.value,
      pageSize: budgetPageSize,
      year: budgetYear.value ?? undefined,
    })
    if (requestId !== budgetRequestId) return
    budgetView.value = result
  } finally {
    if (requestId === budgetRequestId) budgetLoading.value = false
  }
}

function changeBudgetPage(page: number): void {
  budgetPage.value = page
  void loadBudget()
}

function chooseBudgetYear(year: number | null): void {
  budgetYear.value = year
  budgetPage.value = 1
  void loadBudget()
}

async function saveBudget(): Promise<void> {
  const indicator = budgetIndicator.value
  if (!indicator) return
  const raw = budgetAmount.value.trim()
  let totalBudget: number | null = null
  if (raw !== '') {
    if (!/^\d+(\.\d{1,2})?$/.test(raw)) {
      showToast('总预算须为不小于 0 的数字，最多两位小数')
      return
    }
    totalBudget = Number(raw)
  }
  if (!budgetReason.value.trim()) {
    showToast('请填写预算调整原因')
    return
  }
  savingBudget.value = true
  try {
    await putIndicatorBudget(
      indicator.id,
      { version: indicator.version, totalBudget, reason: budgetReason.value.trim() },
      newIdempotencyKey(),
    )
    showToast('预算已保存')
    budgetReason.value = ''
    await loadTree()
    const fresh = tree.value.find((node) => node.id === indicator.id)
    if (fresh) {
      budgetIndicator.value = fresh
      budgetAmount.value = fresh.totalBudget == null ? '' : String(fresh.totalBudget)
    }
    await loadBudget()
  } finally {
    savingBudget.value = false
  }
}

// ---------- 计划切换 ----------
function onPlanChange(planOption: { id: string; name: string }): void {
  router.replace(`/plans/${planOption.id}/indicators`)
}

const filtersVisible = ref(false)

watch(
  planId,
  (id, previousId) => {
    planRequestId += 1
    treeRequestId += 1
    statsRequestId += 1
    directionRequestId += 1
    materialCountRequestId += 1
    budgetRequestId += 1
    budgetVisible.value = false
    budgetIndicator.value = null
    budgetView.value = null
    plan.value = null
    tree.value = []
    yearStatsIndexes.value = new Map()
    focusStats.value = []
    directionList.value = []
    materialCounts.value = {}
    selectedDirectionId.value = ''
    for (const key of Object.keys(tasksByIndicator)) delete tasksByIndicator[key]
    taskLoadingIds.clear()
    taskErrorIds.clear()
    expandedIds.clear()
    if (id && previousId !== id) {
      filters.keyword = ''
      filters.nature = ''
      filters.ownerId = ''
      filters.quantStatus = ''
      filters.completionStatus = ''
      filters.onlyMine = defaultOnlyMine()
    }
    if (id) void loadAll()
  },
  { immediate: true },
)

onMounted(() => {
  void loadPlans()
  getUserOptions().then((list) => {
    userOptions.value = list
  }).catch(() => undefined)
})

const ownerLabel = computed(
  () => userOptions.value.find((item) => item.id === filters.ownerId)?.name ?? '全部负责人',
)
const ownerPickerVisible = ref(false)
const ownerKeyword = ref('')
const filteredOwners = computed(() => {
  const keyword = ownerKeyword.value.trim()
  return keyword ? userOptions.value.filter((user) => user.name.includes(keyword) || user.username.includes(keyword)) : userOptions.value
})
function chooseOwner(userId: string): void {
  filters.ownerId = userId
  ownerPickerVisible.value = false
  ownerKeyword.value = ''
}
</script>

<template>
  <div class="page">
    <PlanSwitcher
      v-if="plans.length > 0"
      :model-value="planId"
      :plans="plans"
      @change="onPlanChange"
    />

    <PageState :loading="loading && !plan" loading-text="加载指标…">
      <template v-if="plan">
        <div class="indicator-head">
          <div>
            <p class="muted small">{{ plan.name }}</p>
            <h1>指标总表</h1>
            <p class="muted small">共 {{ aRows.length }} 项关键产出指标</p>
          </div>
          <van-button
            v-if="canEdit && !readOnly"
            size="small"
            type="primary"
            icon="plus"
            @click="openCreate"
          >新增</van-button>
        </div>

        <div v-if="readOnly" class="archived-tip">该计划已归档，只能查看，不能编辑或下发</div>

        <div class="quick-actions">
          <button type="button" class="quick-btn" @click="goProgress">
            <van-icon name="chart-trending-o" /> 进度可视化
          </button>
          <button v-if="canEdit && !readOnly" type="button" class="quick-btn" @click="importVisible = true">
            <van-icon name="upgrade" /> 导入 Excel
          </button>
          <button type="button" class="quick-btn" :disabled="exporting" @click="handleExport">
            <van-icon name="down" /> {{ exporting ? '导出中…' : '导出 Excel' }}
          </button>
          <button v-if="isAdmin && !readOnly" type="button" class="quick-btn" @click="openAddDirection">
            <van-icon name="add-o" /> 添加方向
          </button>
        </div>

        <van-search v-model="filters.keyword" placeholder="指标编码或名称" shape="round" @search="applyFilters" @clear="applyFilters" />

        <div class="filter-scroll">
          <button
            type="button"
            class="filter-chip"
            :class="{ 'is-active': selectedDirectionId === '' }"
            @click="selectedDirectionId = ''"
          >全部方向</button>
          <button
            v-for="direction in directionOptions"
            :key="direction.id"
            type="button"
            class="filter-chip"
            :class="{ 'is-active': selectedDirectionId === direction.id }"
            @click="selectedDirectionId = direction.id"
          >{{ direction.name }} {{ direction.count }}</button>
        </div>

        <div class="filter-scroll">
          <button
            type="button"
            class="filter-chip"
            :class="{ 'is-active': filters.completionStatus === '' }"
            @click="filters.completionStatus = ''"
          >全部指标</button>
          <button
            type="button"
            class="filter-chip"
            :class="{ 'is-active': filters.completionStatus === 'UNFINISHED' }"
            @click="filters.completionStatus = 'UNFINISHED'"
          >今年未完成</button>
          <button
            type="button"
            class="filter-chip"
            :class="{ 'is-active': filters.completionStatus === 'FINISHED' }"
            @click="filters.completionStatus = 'FINISHED'"
          >今年已完成</button>
          <button type="button" class="filter-chip" @click="filtersVisible = true">更多筛选</button>
        </div>

        <PageState
          :loading="loading && tree.length === 0"
          :empty="visibleTree.length === 0"
          empty-text="当前方向或筛选条件下没有指标"
        >
          <div class="indicator-list">
            <article v-for="node in visibleTree" :key="node.id" class="indicator-card">
              <div class="indicator-card__top">
                <button type="button" class="code-btn" @click="toggleExpand(node)">
                  <van-icon :name="expandedIds.has(node.id) ? 'arrow-down' : 'arrow'" size="12" />
                  {{ node.code }}
                </button>
                <div class="row">
                  <StatusTag kind="IndicatorStatus" :value="node.status" />
                  <StatusTag kind="IndicatorNature" :value="node.nature" />
                </div>
              </div>

              <h2 class="indicator-name" @click="toggleExpand(node)">{{ node.name }}</h2>

              <div class="indicator-meta">
                <StatusTag v-if="node.statCaliber" kind="StatCaliber" :value="node.statCaliber" />
                <span class="muted small">{{ node.owner?.name ?? '未指定负责人' }}</span>
              </div>

              <div class="total-progress" @click="openProgress(node)">
                <div class="row--between">
                  <span class="small muted">五年总进度</span>
                  <strong class="small" :class="aTotalPercent(node) >= 100 ? 'text-success' : ''">{{ aTotalText(node) }}</strong>
                </div>
                <ProgressBar :rate="aTotalPercent(node) / 100" :height="8" />
              </div>

              <div class="indicator-stats">
                <div class="stat-cell">
                  <span>五年目标</span>
                  <strong>{{ totalTargetDisplay(node) }}</strong>
                </div>
                <div class="stat-cell">
                  <span>当年完成</span>
                  <strong>{{ formatPercent(aStats(node.id)?.completionRate) }}</strong>
                </div>
                <button type="button" class="stat-cell stat-cell--link" @click="openMaterials(node)">
                  <span>佐证材料</span>
                  <strong>{{ formatCount(materialCountOf(node.id)) }} 份</strong>
                </button>
              </div>

              <button v-if="node.level === 'A'" type="button" class="budget-row" @click="openBudget(node)">
                <span class="small muted">指标预算</span>
                <strong class="small">{{ budgetCardText(node) }}</strong>
                <van-icon name="arrow" size="12" />
              </button>

              <div v-if="!node.isQuantifiable" class="undecomposable">不可量化 · 仅维护基础信息</div>
              <div v-if="taskErrorIds.has(node.id) && !taskLoadingIds.has(node.id)" class="task-error">
                学院任务加载失败，点击编号重试
              </div>

              <div class="indicator-actions">
                <button type="button" class="action-link" @click="goDetail(node)">详情</button>
                <button
                  type="button"
                  class="action-link"
                  :disabled="!canAllocateRow(node)"
                  @click="goAllocation(node)"
                >任务分解</button>
                <button type="button" class="action-link" @click="toggleExpand(node)">
                  {{ expandedIds.has(node.id) ? '收起' : '展开任务' }}
                </button>
                <button v-if="canEditRow(node)" type="button" class="action-link" @click="openRowActions(node)">
                  更多
                </button>
              </div>

              <div v-if="expandedIds.has(node.id)" class="indicator-expand">
                <div class="year-list">
                  <div v-for="year in years" :key="year" class="year-row">
                    <div class="year-row__head">
                      <strong :class="{ 'text-brand': year === currentYear }">{{ year }}</strong>
                      <span class="small muted">目标 {{ formatTargetValue(targetOf(node, year), node.statCaliber) }}</span>
                      <span class="small">完成 {{ yearActualDisplay(node, year) }}</span>
                    </div>
                    <ProgressBar :rate="yearCompletionPercent(node, year) / 100" :height="6" show-text />
                  </div>
                </div>

                <div class="section-title">学院任务</div>
                <div v-if="taskLoadingIds.has(node.id)" class="skeleton-block">加载学院任务…</div>
                <div v-else-if="(tasksByIndicator[node.id] ?? []).length === 0" class="empty-state">
                  {{ node.isQuantifiable ? '尚未分解学院任务' : '不可量化指标不生成任务' }}
                </div>
                <div v-else class="task-list">
                  <button
                    v-for="task in tasksByIndicator[node.id]"
                    :key="task.id"
                    type="button"
                    class="task-row"
                    @click="openTaskDetail(task)"
                  >
                    <div class="row--between">
                      <strong>{{ task.code }}</strong>
                      <StatusTag kind="TaskStatus" :value="task.status" />
                    </div>
                    <div class="small muted ellipsis">{{ task.unit?.name ?? '—' }} · {{ task.owner?.name ?? '未指定负责人' }}</div>
                    <div class="task-row__meta">
                      <StatusTag v-if="task.yearStatus" kind="TaskYearStatus" :value="task.yearStatus" />
                      <span class="small">目标 {{ formatTargetValue(task.yearTarget ?? null, node.statCaliber) }}</span>
                      <span class="small">完成 {{ formatTargetValue(task.actualValue ?? null, node.statCaliber) }}</span>
                    </div>
                    <div class="task-row__actions">
                      <button type="button" class="text-btn" @click.stop="openTaskDetail(task)">详情</button>
                      <button type="button" class="text-btn" @click.stop="openTaskMaterials(task, currentYear)">材料</button>
                    </div>
                  </button>
                </div>
              </div>
            </article>
          </div>
        </PageState>

        <p class="table-footer">
          共 {{ aRows.length }} 条指标；当前显示 {{ visibleTree.length }} 条；约束性 {{ constraintCount }} / 发展性 {{ developmentCount }}
        </p>
      </template>
    </PageState>

    <!-- 更多筛选 -->
    <van-popup v-model:show="filtersVisible" position="bottom" round safe-area-inset-bottom>
      <div class="filter-sheet">
        <div class="sheet-head">
          <strong>更多筛选</strong>
          <button type="button" class="close-btn" @click="filtersVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="field">
          <label>指标性质</label>
          <div class="chip-group">
            <button type="button" class="filter-chip" :class="{ 'is-active': filters.nature === '' }" @click="filters.nature = ''">全部</button>
            <button type="button" class="filter-chip" :class="{ 'is-active': filters.nature === 'CONSTRAINT' }" @click="filters.nature = 'CONSTRAINT'">约束性</button>
            <button type="button" class="filter-chip" :class="{ 'is-active': filters.nature === 'DEVELOPMENT' }" @click="filters.nature = 'DEVELOPMENT'">发展性</button>
          </div>
        </div>
        <div class="field">
          <label>量化状态</label>
          <div class="chip-group">
            <button type="button" class="filter-chip" :class="{ 'is-active': filters.quantStatus === '' }" @click="filters.quantStatus = ''">全部</button>
            <button type="button" class="filter-chip" :class="{ 'is-active': filters.quantStatus === 'QUANTIFIABLE' }" @click="filters.quantStatus = 'QUANTIFIABLE'">可量化</button>
            <button type="button" class="filter-chip" :class="{ 'is-active': filters.quantStatus === 'NON_QUANTIFIABLE' }" @click="filters.quantStatus = 'NON_QUANTIFIABLE'">不可量化</button>
          </div>
        </div>
        <div class="field">
          <label>负责人</label>
          <van-field
            readonly
            is-link
            :model-value="ownerLabel"
            :disabled="filters.onlyMine"
            @click="ownerPickerVisible = true"
          />
        </div>
        <van-checkbox v-model="filters.onlyMine">只看我负责的</van-checkbox>
        <div class="sheet-actions">
          <van-button block @click="filters.nature = ''; filters.ownerId = ''; filters.quantStatus = ''; filters.completionStatus = ''; filters.onlyMine = defaultOnlyMine(); filters.keyword = ''; selectedDirectionId = ''; filtersVisible = false; applyFilters()">重置全部</van-button>
          <van-button block type="primary" @click="filtersVisible = false; applyFilters()">应用筛选</van-button>
        </div>
      </div>
    </van-popup>

    <van-action-sheet
      v-model:show="rowActionVisible"
      :actions="rowActionItems"
      cancel-text="取消"
      close-on-click-action
      @select="handleRowAction"
    />

    <!-- 负责人选择 -->
    <van-popup v-model:show="ownerPickerVisible" position="bottom" round :style="{ height: '60vh' }">
      <div class="progress-sheet">
        <div class="sheet-head">
          <strong>选择负责人</strong>
          <button type="button" class="close-btn" @click="ownerPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <van-search v-model="ownerKeyword" placeholder="搜索姓名 / 工号" shape="round" />
        <div class="sheet-body">
          <button type="button" class="owner-item" @click="chooseOwner('')">全部负责人</button>
          <button
            v-for="user in filteredOwners"
            :key="user.id"
            type="button"
            class="owner-item"
            @click="chooseOwner(user.id)"
          >{{ user.name }}（{{ user.username }}）</button>
        </div>
      </div>
    </van-popup>

    <!-- 方向新增 -->
    <van-popup v-model:show="directionVisible" position="bottom" round safe-area-inset-bottom>
      <div class="simple-sheet">
        <h3>添加主要建设方向</h3>
        <van-field v-model="directionName" placeholder="例如：落实立德树人根本任务" maxlength="100" />
        <div class="sheet-actions">
          <van-button block @click="directionVisible = false">取消</van-button>
          <van-button block type="primary" :loading="savingDirection" @click="saveDirection">添加</van-button>
        </div>
      </div>
    </van-popup>

    <!-- 五年进度 -->
    <van-popup v-model:show="progressVisible" position="bottom" round :style="{ height: '78vh' }">
      <div class="progress-sheet">
        <div class="sheet-head">
          <strong>五年详细进度</strong>
          <button type="button" class="close-btn" @click="progressVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="sheet-body">
          <p class="muted small">{{ progressIndicator?.code }} {{ progressIndicator?.name }}</p>
          <div v-if="progressLoading" class="skeleton-block">加载中…</div>
          <template v-else-if="progressTrend">
            <div class="progress-summary">
              <div><span>五年完成率</span><strong>{{ formatPercent(progressTrend.cumulativeRate) }}</strong></div>
              <div><span>需提交</span><strong>{{ progressSummary.requiredCount }}</strong></div>
              <div><span>达标</span><strong>{{ progressSummary.achievedCount }}</strong></div>
              <div><span>进行中</span><strong>{{ progressSummary.inProgressCount }}</strong></div>
            </div>
            <ProgressBar :rate="progressTrend.cumulativeRate" :height="10" show-text />
            <div class="trend-list">
              <div v-for="year in progressTrend.years" :key="year.year" class="trend-item">
                <div class="row--between">
                  <strong>{{ year.year }} 年</strong>
                  <span class="small muted">目标 {{ formatTargetValue(year.target, progressTrend.statCaliber) }} · 完成 {{ formatTargetValue(year.actualValue, progressTrend.statCaliber) }}</span>
                </div>
                <ProgressBar :rate="year.completionRate" :height="6" show-text />
              </div>
            </div>
          </template>
        </div>
      </div>
    </van-popup>

    <!-- 佐证材料 -->
    <van-popup v-model:show="materialVisible" position="bottom" round :style="{ height: '78vh' }">
      <div class="progress-sheet">
        <div class="sheet-head">
          <strong>佐证材料 · {{ materialIndicator?.code }}</strong>
          <button type="button" class="close-btn" @click="materialVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="sheet-body">
          <div v-if="materialLoading" class="skeleton-block">加载中…</div>
          <div v-else-if="materialRows.length === 0" class="empty-state">该指标暂无佐证材料</div>
          <div v-else class="material-list">
            <AttachmentList :files="materialRows" empty-text="该指标暂无佐证材料" />
            <div class="pager">
              <van-button size="small" :disabled="materialPage <= 1" @click="changeMaterialPage(materialPage - 1)">上一页</van-button>
              <span class="small muted">{{ materialPage }} / {{ Math.max(1, Math.ceil(materialTotal / materialPageSize)) }}</span>
              <van-button
                size="small"
                :disabled="materialPage >= Math.ceil(materialTotal / materialPageSize)"
                @click="changeMaterialPage(materialPage + 1)"
              >下一页</van-button>
            </div>
          </div>
        </div>
      </div>
    </van-popup>

    <!-- 指标预算 -->
    <van-popup v-model:show="budgetVisible" position="bottom" round :style="{ height: '78vh' }">
      <div class="progress-sheet">
        <div class="sheet-head">
          <strong>指标预算 · {{ budgetIndicator?.code }}</strong>
          <button type="button" class="close-btn" @click="budgetVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="sheet-body">
          <div v-if="budgetLoading && !budgetView" class="skeleton-block">加载中…</div>
          <template v-else>
            <div class="budget-summary">
              <div><span>总预算</span><strong>{{ budgetView?.totalBudget == null ? '未设置' : `${formatMoney(budgetView.totalBudget)} 万` }}</strong></div>
              <div><span>已花预算</span><strong>{{ formatMoney(budgetView?.spentBudget ?? 0) }} 万</strong></div>
              <div><span>剩余预算</span><strong>{{ budgetRemainingText }}</strong></div>
            </div>

            <div v-if="canBudget && !isArchived" class="budget-form">
              <div class="field">
                <label>总预算（万元）</label>
                <van-field
                  v-model="budgetAmount"
                  type="number"
                  inputmode="decimal"
                  placeholder="留空保存表示清除预算"
                />
              </div>
              <div class="field">
                <label>调整原因（必填）</label>
                <van-field
                  v-model="budgetReason"
                  type="textarea"
                  rows="2"
                  maxlength="500"
                  show-word-limit
                  placeholder="预算维护属于敏感操作，必须填写原因"
                />
              </div>
              <van-button block type="primary" size="small" :loading="savingBudget" @click="saveBudget">保存预算</van-button>
            </div>

            <div class="chip-group budget-year-filter">
              <button type="button" class="filter-chip" :class="{ 'is-active': budgetYear === null }" @click="chooseBudgetYear(null)">全部年度</button>
              <button
                v-for="year in years"
                :key="year"
                type="button"
                class="filter-chip"
                :class="{ 'is-active': budgetYear === year }"
                @click="chooseBudgetYear(year)"
              >{{ year }}</button>
            </div>

            <div class="section-title">支出明细</div>
            <div v-if="budgetLoading" class="skeleton-block">加载中…</div>
            <div v-else-if="(budgetView?.expenses.items.length ?? 0) === 0" class="empty-state">暂无支出记录</div>
            <div v-else class="budget-expense-list">
              <div v-for="expense in budgetView?.expenses.items ?? []" :key="expense.submissionId" class="budget-expense">
                <div class="row--between">
                  <strong>{{ expense.taskCode ?? '—' }}</strong>
                  <StatusTag kind="SubmissionStatus" :value="expense.status" />
                </div>
                <div class="small muted">{{ expense.unit?.name ?? '—' }} · {{ expense.year }} 年 · {{ expense.submittedBy?.name ?? '—' }}</div>
                <div class="small">
                  申报支出 {{ expense.expenseAmount == null ? '—' : `${formatMoney(expense.expenseAmount)} 万` }}
                  <template v-if="expense.approvedExpenseAmount != null"> · 认定 {{ formatMoney(expense.approvedExpenseAmount) }} 万</template>
                </div>
                <div class="small muted">{{ expense.submittedAt ? formatDateTime(expense.submittedAt) : '—' }}</div>
                <AttachmentList
                  v-if="expense.budgetFiles.length > 0"
                  :files="asAttachments(expense.budgetFiles)"
                  compact
                  empty-text="无预算佐证"
                />
              </div>
              <div class="pager">
                <van-button size="small" :disabled="budgetPage <= 1" @click="changeBudgetPage(budgetPage - 1)">上一页</van-button>
                <span class="small muted">{{ budgetPage }} / {{ Math.max(1, Math.ceil((budgetView?.expenses.total ?? 0) / budgetPageSize)) }}</span>
                <van-button
                  size="small"
                  :disabled="budgetPage >= Math.ceil((budgetView?.expenses.total ?? 0) / budgetPageSize)"
                  @click="changeBudgetPage(budgetPage + 1)"
                >下一页</van-button>
              </div>
            </div>
          </template>
        </div>
      </div>
    </van-popup>

    <IndicatorFormSheet
      v-model="formVisible"
      :plan-id="planId"
      :indicator="editingIndicator"
      :years="years"
      :direction-options="directionOptions.map((item) => ({ id: item.id, name: item.name }))"
      :plan-archived="isArchived"
      @saved="onSaved"
    />
    <ImportSheet v-model="importVisible" :plan-id="planId" @imported="loadTree" />
    <TaskDetailSheet v-model="taskSheetVisible" :task-id="taskSheetId" />
  </div>
</template>

<style scoped>
.indicator-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 0 10px;
}
.indicator-head h1 {
  margin: 2px 0;
  font-size: 22px;
}
.indicator-head p {
  margin: 0;
}
.archived-tip {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f1f3f7;
  color: var(--text-secondary);
  font-size: 12px;
}
.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.quick-btn {
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
.indicator-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 6px;
}
.indicator-card {
  padding: 12px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.indicator-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.code-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 30px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
}
.indicator-name {
  margin: 8px 0 4px;
  font-size: 15px;
  line-height: 21px;
}
.indicator-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.total-progress {
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  background: #f8fafd;
}
.total-progress .progress-wrap {
  margin-top: 4px;
}
.indicator-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin-bottom: 10px;
}
.stat-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 48px;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
  color: var(--text);
  text-align: left;
}
.stat-cell span {
  color: var(--text-secondary);
  font-size: 11px;
}
.stat-cell strong {
  font-size: 13px;
}
.stat-cell--link {
  color: var(--brand);
}
.undecomposable {
  margin-bottom: 8px;
  color: var(--text-secondary);
  font-size: 11px;
}
.task-error {
  margin-bottom: 8px;
  color: var(--danger);
  font-size: 11px;
}
.indicator-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-top: 8px;
  border-top: 1px solid var(--line);
}
.action-link {
  min-height: 32px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 13px;
}
.action-link:disabled {
  color: var(--text-placeholder);
}
.more-menu {
  margin-left: auto;
}
.more-menu :deep(.van-dropdown-menu__bar) {
  height: 32px;
  box-shadow: none;
  background: transparent;
}
.more-menu :deep(.van-dropdown-menu__title) {
  color: var(--brand);
  font-size: 13px;
}
.more-menu :deep(.van-dropdown-menu__title::after) {
  border-color: var(--brand) transparent transparent;
}
.indicator-expand {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}
.year-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.year-row {
  padding: 8px;
  border-radius: 8px;
  background: #f8fafd;
}
.year-row__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}
.task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.task-row {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  text-align: left;
  color: var(--text);
}
.task-row__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.task-row__actions {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}
.text-btn {
  min-height: 30px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 12px;
}
.table-footer {
  margin: 12px 0 0;
  color: var(--text-placeholder);
  font-size: 11px;
  text-align: center;
}
.filter-sheet,
.simple-sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
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
.field {
  margin-bottom: 12px;
}
.field label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.field :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.chip-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.sheet-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}
.simple-sheet h3 {
  margin: 0 0 12px;
  font-size: 16px;
}
.simple-sheet :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.progress-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.progress-sheet .sheet-head {
  margin: 0;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}
.sheet-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px calc(16px + var(--safe-bottom));
}
.progress-summary {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  margin: 10px 0;
}
.progress-summary > div {
  display: flex;
  flex-direction: column;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafd;
}
.progress-summary span {
  color: var(--text-secondary);
  font-size: 11px;
}
.progress-summary strong {
  font-size: 15px;
}
.trend-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}
.trend-item {
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.material-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.material-item {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 10px 0;
}
.budget-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 40px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  color: var(--text);
  text-align: left;
}
.budget-row strong {
  margin-left: auto;
  color: var(--brand);
}
.budget-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}
.budget-summary > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafd;
}
.budget-summary span {
  color: var(--text-secondary);
  font-size: 11px;
}
.budget-summary strong {
  font-size: 13px;
}
.budget-form {
  margin-bottom: 12px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.budget-year-filter {
  margin-bottom: 4px;
}
.budget-expense-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.budget-expense {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.owner-item {
  width: 100%;
  min-height: 46px;
  padding: 8px 4px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
</style>
