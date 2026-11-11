<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { ArrowDown, Delete, Edit, Expand, Fold } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import ResponsiveRecordView from '@/components/ResponsiveRecordView.vue'
import FilePreviewDrawer from '@/components/FilePreviewDrawer.vue'
import IndicatorEditDrawer from './components/IndicatorEditDrawer.vue'
import IndicatorTaskPanel from './components/IndicatorTaskPanel.vue'
import ImportDialog from './components/ImportDialog.vue'
import RollupBadge from './components/RollupBadge.vue'
import TaskDetailDrawer from '@/views/workbench/components/TaskDetailDrawer.vue'
import TaskAllocationModal from '@/views/allocation/TaskAllocationModal.vue'
import { getPlan, listPlans } from '@/api/plan'
import { createDirection, deleteDirection, listDirections } from '@/api/direction'
import { deleteIndicator, listIndicators } from '@/api/indicator'
import { listAllTasks } from '@/api/task'
import { createExport, pollExport } from '@/api/export'
import { getIndicatorTrend, getStatsTree } from '@/api/stats'
import { countMaterialsByIndicator, listMaterials } from '@/api/material'
import type { MaterialItem } from '@/api/material'
import { downloadFile } from '@/api/file'
import { getUserOptions } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { enumLabel } from '@/utils/enumLabels'
import { newIdempotencyKey } from '@/utils/idempotency'
import {
  booleanPlanYear,
  completionLevel,
  completionLevelClass,
  completionPercent,
  exceededAmount,
  formatActualValue,
  formatTargetValue,
  hasQuantitativeFields,
  indexStatsTree,
  isPercentCaliber,
  parseDirectionIdQuery,
  parseExpandedIndicatorIds,
  serializeExpandedIndicatorIds,
  yearTargetMap,
  type CompletionLevel,
  type StatsTreeIndex,
} from '@/utils/indicator'
import { formatCount, formatDateTime, formatNumber, formatPercent } from '@/utils/format'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { formatFileSize, triggerFileDownload } from '@/utils/file'
import type { Direction, PlanDetail } from '@/types/plan'
import type { Indicator, IndicatorTreeNode } from '@/types/indicator'
import type { IndicatorTrend, StatsTreeNode, TrendYearItem } from '@/types/stats'
import type { StatCaliber } from '@/types/enums'
import type { IndicatorNature } from '@/types/enums'
import type { TaskListItem } from '@/types/task'
import type { UserOption } from '@/types/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const planId = computed(() => (route.params.planId as string) || (route.query.planId as string) || '')

const loading = ref(false)
const plan = ref<PlanDetail | null>(null)
const tree = ref<IndicatorTreeNode[]>([])
const statsTree = ref<StatsTreeNode[]>([])
const yearStatsIndexes = ref<Map<number, StatsTreeIndex>>(new Map())
const directionList = ref<Direction[]>([])
const userOptions = ref<UserOption[]>([])
const selectedDirectionId = ref('')
const DIRECTION_SIDEBAR_COLLAPSED_KEY = 'fzsu.indicatorDirectionSidebarCollapsed'
function readDirectionSidebarCollapsed(): boolean {
  try {
    return localStorage.getItem(DIRECTION_SIDEBAR_COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}
const directionSidebarCollapsed = ref(readDirectionSidebarCollapsed())
function toggleDirectionSidebar(): void {
  directionSidebarCollapsed.value = !directionSidebarCollapsed.value
  try {
    localStorage.setItem(DIRECTION_SIDEBAR_COLLAPSED_KEY, directionSidebarCollapsed.value ? '1' : '0')
  } catch {
    // 存储不可用时退化为当前会话内记忆。
  }
}
let planRequestId = 0
let treeRequestId = 0
let statsRequestId = 0
let directionRequestId = 0
let expandAllRequestId = 0
const EXPAND_ALL_CONCURRENCY = 6
/** 进入页面最多恢复的展开行数，避免一次预加载上百条 B 任务 */
const MAX_RESTORED_EXPANDED = 20

const defaultOnlyMine = (): boolean => userStore.roles.includes('SCHOOL_OWNER')
const filters = reactive({
  nature: '' as IndicatorNature | '',
  ownerId: '',
  quantStatus: '' as '' | 'QUANTIFIABLE' | 'NON_QUANTIFIABLE',
  completionStatus: '' as '' | 'UNFINISHED' | 'FINISHED',
  onlyMine: defaultOnlyMine(),
  keyword: '',
})

const canEdit = computed(() => userStore.can('indicator:edit'))
const canAdjust = computed(() => userStore.can('adjustment:apply'))
const canAllocate = computed(() => userStore.can('allocation:edit'))
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))
const isArchived = computed(() => plan.value?.status === 'ARCHIVED')
const readOnly = computed(() => isArchived.value)
/** 编辑模式：开启后在主要建设方向和关键产出指标旁显示删除图标 */
const editMode = ref(false)
const canEnterEditMode = computed(() => !readOnly.value && (canEdit.value || isAdmin.value))
const canDeleteDirection = computed(() => isAdmin.value && !readOnly.value)
const years = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) list.push(year)
  return list
})
const currentYear = new Date().getFullYear()
const focusYear = computed(() => (years.value.includes(currentYear) ? currentYear : (years.value[0] ?? currentYear)))
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
    const focusIndex = Math.max(0, planYears.indexOf(focusYear.value))
    statsTree.value = trees[focusIndex] ?? []
  } catch {
    if (requestId === statsRequestId && requestedPlanId === planId.value) {
      statsTree.value = []
      yearStatsIndexes.value = new Map()
    }
  }
}

async function loadDirections(): Promise<void> {
  const requestedPlanId = planId.value
  const requestId = ++directionRequestId
  if (!requestedPlanId || !isAdmin.value) {
    if (requestId === directionRequestId) directionList.value = []
    return
  }
  try {
    const result = await listDirections(requestedPlanId)
    if (requestId === directionRequestId && requestedPlanId === planId.value) directionList.value = result
  } catch {
    if (requestId === directionRequestId && requestedPlanId === planId.value) directionList.value = []
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
  await syncExpandedNodes()
}

async function loadAll(silent = false): Promise<void> {
  const requestedPlanId = planId.value
  if (!requestedPlanId) return
  const requestId = ++planRequestId
  if (!silent) loading.value = true
  try {
    const detail = await getPlan(requestedPlanId)
    if (requestId !== planRequestId || requestedPlanId !== planId.value) return
    plan.value = detail
    await Promise.all([
      loadTree(),
      loadStatsTree(),
      loadDirections(),
      loadMaterialCounts(),
    ])
    if (silent) {
      const loadedIds = Object.keys(tasksByIndicator)
      loadedIds.forEach(invalidateIndicatorTasks)
      await Promise.all(loadedIds.map((id) => ensureIndicatorTasks(id).catch(() => undefined)))
    }
  } finally {
    if (!silent && requestId === planRequestId) loading.value = false
  }
}

function applyFilters(): void {
  loading.value = true
  loadTree().finally(() => {
    loading.value = false
  })
}

let keywordTimer: ReturnType<typeof setTimeout> | null = null
function onKeywordInput(): void {
  if (keywordTimer !== null) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    keywordTimer = null
    applyFilters()
  }, 300)
}

function resetFilters(): void {
  if (keywordTimer !== null) {
    clearTimeout(keywordTimer)
    keywordTimer = null
  }
  filters.nature = ''
  filters.ownerId = ''
  filters.quantStatus = ''
  filters.completionStatus = ''
  filters.onlyMine = defaultOnlyMine()
  filters.keyword = ''
  selectDirection('')
  applyFilters()
}

const visibleTree = computed<IndicatorTreeNode[]>(() => {
  let nodes = tree.value
  if (selectedDirectionId.value === '__UNASSIGNED__') {
    nodes = nodes.filter((node) => !node.directionId)
  } else if (selectedDirectionId.value) {
    nodes = nodes.filter((node) => node.directionId === selectedDirectionId.value)
  }
  if (filters.quantStatus === 'QUANTIFIABLE') nodes = nodes.filter((node) => node.isQuantifiable)
  if (filters.quantStatus === 'NON_QUANTIFIABLE') nodes = nodes.filter((node) => !node.isQuantifiable)
  if (filters.completionStatus) {
    const year = new Date().getFullYear()
    nodes = nodes.filter((node) => {
      if (!node.isQuantifiable || !hasTargetValue(targetOf(node, year))) return false
      const stats = aStatsOfYear(node.id, year)
      const achieved = stats?.achieveStatus === 'ACHIEVED' || stats?.achieveStatus === 'EXCEEDED'
      return filters.completionStatus === 'FINISHED' ? achieved : !achieved
    })
  }
  if (filters.nature) {
    nodes = nodes
      .map((node) => ({
        ...node,
        children: node.children.filter((child) => child.nature === filters.nature),
      }))
      .filter((node) => node.nature === filters.nature || node.children.length > 0)
  }
  return nodes
})

const directionOptions = computed(() => {
  const options = new Map<string, { id: string; code: string | null; name: string }>()
  for (const direction of directionList.value) {
    options.set(direction.id, { id: direction.id, code: direction.code, name: direction.name })
  }
  for (const indicator of tree.value) {
    if (indicator.directionId && !options.has(indicator.directionId)) {
      options.set(indicator.directionId, {
        id: indicator.directionId,
        code: indicator.directionCode ?? null,
        name: indicator.directionName ?? '未命名建设方向',
      })
    }
  }
  return [...options.values()]
})

const directionNavItems = computed(() => {
  const items = directionOptions.value.map((direction) => ({
    ...direction,
    count: tree.value.filter((indicator) => indicator.directionId === direction.id).length,
  }))
  const unassignedCount = tree.value.filter((indicator) => !indicator.directionId).length
  if (unassignedCount > 0) {
    items.push({ id: '__UNASSIGNED__', code: null, name: '未分配建设方向', count: unassignedCount })
  }
  return items
})

function syncDirectionQuery(id: string): void {
  if (parseDirectionIdQuery(route.query.directionId) === id && (id || route.query.directionId === undefined)) return
  const query = { ...route.query }
  if (id) query.directionId = id
  else delete query.directionId
  void router.replace({ query })
}

function selectDirection(id: string): void {
  const directionId = parseDirectionIdQuery(id)
  selectedDirectionId.value = directionId
  syncDirectionQuery(directionId)
}

async function addDirection(): Promise<void> {
  if (!isAdmin.value || readOnly.value) return
  try {
    const name = await ElMessageBox.prompt('请输入主要建设方向名称', '添加主要建设方向', {
      confirmButtonText: '添加',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：落实立德树人根本任务',
      inputValidator: (value: string) => value.trim().length > 0 || '方向名称不能为空',
    })
    const nextSort = Math.max(0, ...directionList.value.map((item) => item.sort ?? 0)) + 1
    await createDirection(planId.value, { name: name.value.trim(), sort: nextSort, leaderId: null })
    ElMessage.success('主要建设方向已添加')
    await loadDirections()
  } catch {
    // 取消或接口异常分别由弹窗和全局拦截器处理。
  }
}

interface ATableRow {
  kind: 'indicator'
  id: string
  indicator: IndicatorTreeNode
  hasChildren: boolean
}

function toIndicatorRow(indicator: IndicatorTreeNode): ATableRow {
  return {
    kind: 'indicator',
    id: indicator.id,
    indicator,
    hasChildren: hasQuantitativeFields(indicator.level, indicator.isQuantifiable),
  }
}

const lazyTree = computed<ATableRow[]>(() => visibleTree.value.map(toIndicatorRow))
const expandedRowKeys = computed(() => [...expandedIds])
const expandAll = ref(false)
const expandAllLoading = ref(false)
const expandedIds = reactive(new Set<string>())
const tasksByIndicator = reactive<Record<string, TaskListItem[]>>({})
const taskLoadingIds = reactive(new Set<string>())
const taskErrorIds = reactive(new Set<string>())
const taskRequests = new Map<string, Promise<TaskListItem[]>>()
const taskRequestGenerations = new Map<string, number>()
const expandTimers = new Map<string, ReturnType<typeof setTimeout>>()

function expansionStorageKey(): string {
  return `fzsu.indicatorExpanded.${planId.value}`
}

function restoreExpandedState(): void {
  expandedIds.clear()
  if (!planId.value) return
  try {
    const stored = parseExpandedIndicatorIds(localStorage.getItem(expansionStorageKey()))
    // 恢复过多展开会逐条请求 B 任务，超过上限时不恢复，避免进入页面长时间加载
    if (stored.length > MAX_RESTORED_EXPANDED) {
      localStorage.removeItem(expansionStorageKey())
      return
    }
    for (const id of stored) expandedIds.add(id)
  } catch {
    // 存储不可用时仍允许当前会话展开。
  }
}

function persistExpandedState(): void {
  if (!planId.value) return
  try {
    localStorage.setItem(expansionStorageKey(), serializeExpandedIndicatorIds(expandedIds))
  } catch {
    // 存储不可用时退化为当前会话内记忆。
  }
}

function hasLoadedTasks(indicatorId: string): boolean {
  return Object.prototype.hasOwnProperty.call(tasksByIndicator, indicatorId)
}

async function ensureIndicatorTasks(indicatorId: string): Promise<TaskListItem[]> {
  if (hasLoadedTasks(indicatorId)) return tasksByIndicator[indicatorId]
  const pending = taskRequests.get(indicatorId)
  if (pending) return pending
  const requestedPlanId = planId.value
  const generation = taskRequestGenerations.get(indicatorId) ?? 0
  const isCurrent = (): boolean => requestedPlanId === planId.value
    && generation === (taskRequestGenerations.get(indicatorId) ?? 0)
  taskLoadingIds.add(indicatorId)
  taskErrorIds.delete(indicatorId)
  const request = listAllTasks({ indicatorId })
    .then((tasks) => {
      if (isCurrent()) tasksByIndicator[indicatorId] = tasks
      return tasks
    })
    .catch((error: unknown) => {
      if (isCurrent()) taskErrorIds.add(indicatorId)
      throw error
    })
    .finally(() => {
      if (isCurrent()) taskLoadingIds.delete(indicatorId)
      if (taskRequests.get(indicatorId) === request) taskRequests.delete(indicatorId)
    })
  taskRequests.set(indicatorId, request)
  return request
}

function invalidateIndicatorTasks(indicatorId: string): void {
  taskRequestGenerations.set(indicatorId, (taskRequestGenerations.get(indicatorId) ?? 0) + 1)
  taskRequests.delete(indicatorId)
  taskLoadingIds.delete(indicatorId)
  taskErrorIds.delete(indicatorId)
  delete tasksByIndicator[indicatorId]
}

function updateExpandAllState(): void {
  expandAll.value = lazyTree.value.length > 0
    && lazyTree.value.every((node) => !node.hasChildren || expandedIds.has(node.id))
}

function handleExpandChange(row: { id: string }, expanded: boolean): void {
  if (expanded) expandedIds.add(row.id)
  else expandedIds.delete(row.id)
  updateExpandAllState()
  persistExpandedState()
}

async function preloadIndicatorTasks(rows: ATableRow[]): Promise<boolean[]> {
  const loaded = new Array<boolean>(rows.length).fill(false)
  let nextIndex = 0
  const worker = async (): Promise<void> => {
    while (nextIndex < rows.length) {
      const index = nextIndex
      nextIndex += 1
      const row = rows[index]
      if (!row) continue
      try {
        await ensureIndicatorTasks(row.id)
        loaded[index] = true
      } catch {
        loaded[index] = false
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(EXPAND_ALL_CONCURRENCY, rows.length) }, () => worker()))
  return loaded
}

async function toggleExpandAll(): Promise<void> {
  if (expandAllLoading.value) return
  const requestId = ++expandAllRequestId
  expandAllLoading.value = true
  const next = !expandAll.value
  try {
    if (next) {
      const requestedPlanId = planId.value
      const rows = lazyTree.value.filter((row) => row.hasChildren)
      const loaded = await preloadIndicatorTasks(rows)
      if (requestedPlanId !== planId.value) return
      rows.forEach((row, index) => {
        if (loaded[index]) expandedIds.add(row.id)
      })
    } else {
      for (const row of lazyTree.value) expandedIds.delete(row.id)
    }
    updateExpandAllState()
    persistExpandedState()
    await nextTick()
  } finally {
    if (requestId === expandAllRequestId) expandAllLoading.value = false
  }
}

async function syncExpandedNodes(): Promise<void> {
  await nextTick()
  updateExpandAllState()
  const expandedRows = lazyTree.value.filter((row) => row.hasChildren && expandedIds.has(row.id))
  if (expandedRows.length > 0) {
    // 展开状态恢复后异步补齐 B 任务，不阻塞页面首屏
    setTimeout(() => { void preloadIndicatorTasks(expandedRows) }, 0)
  }
}

async function performToggleExpansion(row: ATableRow): Promise<void> {
  const expanded = !expandedIds.has(row.id)
  if (expanded) {
    try {
      await ensureIndicatorTasks(row.id)
    } catch {
      return
    }
  }
  if (!lazyTree.value.some((item) => item.id === row.id)) return
  handleExpandChange(row, expanded)
}

function toggleExpansion(row: ATableRow): void {
  const pending = expandTimers.get(row.id)
  if (pending) clearTimeout(pending)
  expandTimers.set(row.id, setTimeout(() => {
    expandTimers.delete(row.id)
    void performToggleExpansion(row)
  }, 200))
}

function handleTableExpandChange(row: ATableRow, expanded: boolean): void {
  if (expanded !== expandedIds.has(row.id)) toggleExpansion(row)
}

async function retryTasks(indicatorId: string): Promise<void> {
  invalidateIndicatorTasks(indicatorId)
  const row = lazyTree.value.find((item) => item.id === indicatorId)
  if (!row) return
  try {
    await ensureIndicatorTasks(indicatorId)
  } catch {
    // 面板保留错误状态和重试入口。
  }
  if (lazyTree.value.some((item) => item.id === indicatorId)) {
    handleExpandChange(row, true)
  }
}

const statsIndex = computed(() => indexStatsTree(statsTree.value))
const statsByTask = computed(() => statsIndex.value.tasks)
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

/** 指定年度的 A 统计节点（完成值、完成率来自后端统计树） */
function aStatsOfYear(indicatorId: string, year: number): StatsTreeNode | undefined {
  return yearStatsIndexes.value.get(year)?.indicators.get(indicatorId)
}

/** 年度完成进度条：按实际完成率计算，无材料/未审核通过时为 0% */
function yearCompletionPercent(indicator: Indicator, year: number): number {
  const stats = aStatsOfYear(indicator.id, year)
  return completionPercent(stats?.completionRate, stats?.actualValue, targetOf(indicator, year))
}

/** GROWTH 的 actualValue 是增长率，年度完成展示与超额判断统一使用完成总量。 */
function yearActualValue(indicator: Indicator, year: number): number | null | undefined {
  const stats = aStatsOfYear(indicator.id, year)
  return indicator.statCaliber === 'GROWTH' ? stats?.actualTotal : stats?.actualValue
}

/** 年度超额完成值：实际值大于目标时返回差值 */
function yearOverValue(indicator: Indicator, year: number): number | null {
  if (indicator.statCaliber === 'BOOLEAN') return null
  return exceededAmount(yearActualValue(indicator, year), targetOf(indicator, year))
}

function yearActualDisplay(indicator: Indicator, year: number): string {
  return formatActualValue(yearActualValue(indicator, year), indicator.statCaliber)
}

function yearOverDisplay(indicator: Indicator, year: number): string {
  const over = yearOverValue(indicator, year)
  return over === null ? '' : `超额 +${formatTargetValue(over, indicator.statCaliber)}`
}

function hasTargetValue(target: number | boolean | null | undefined): boolean {
  return typeof target === 'boolean' ? target : typeof target === 'number' && target > 0
}

/** A 指标当前年度完成程度 */
function aCurrentLevel(indicatorId: string): CompletionLevel | null {
  const stats = aStats(indicatorId)
  const percent = completionPercent(stats?.completionRate, stats?.actualValue, stats?.target)
  return completionLevel(percent, hasTargetValue(stats?.target) || percent > 0)
}

/** A 指标五年累计进度（0–100） */
function aTotalPercent(indicator: Indicator): number {
  const stats = aStats(indicator.id)
  return completionPercent(stats?.cumulativeRate, stats?.cumulativeActual, indicator.totalTarget)
}

/** A 指标五年累计完成程度 */
function aCumulativeLevel(indicator: Indicator): CompletionLevel | null {
  const percent = aTotalPercent(indicator)
  return completionLevel(percent, hasTargetValue(indicator.totalTarget) || percent > 0)
}

/** 总进度文本：有目标时即使无完成数据也显示 0.0%，无可统计目标时显示 — */
function aTotalText(indicator: Indicator): string {
  const stats = aStats(indicator.id)
  if (typeof stats?.cumulativeRate === 'number') return formatPercent(stats.cumulativeRate)
  const percent = aTotalPercent(indicator)
  return hasTargetValue(indicator.totalTarget) || percent > 0 ? `${percent.toFixed(1)}%` : '—'
}

const INDICATOR_NAME_MAX_LENGTH = 20

/** 关键产出指标名称最多展示 20 个字符，超出部分省略；完整名称由 title 悬浮展示 */
function indicatorNameDisplay(name: string): string {
  const characters = Array.from(name)
  return characters.length > INDICATOR_NAME_MAX_LENGTH
    ? `${characters.slice(0, INDICATOR_NAME_MAX_LENGTH).join('')}...`
    : name
}

/** 年度完成程度：0 红、0–100 黄、≥100 绿 */
function yearCompletionLevel(indicator: Indicator, year: number): CompletionLevel | null {
  const percent = yearCompletionPercent(indicator, year)
  return completionLevel(percent, hasTargetValue(targetOf(indicator, year)) || percent > 0)
}

function aStats(indicatorId: string): StatsTreeNode | undefined {
  return statsIndex.value.indicators.get(indicatorId)
}

function progressPercentage(value: number | null | undefined): number {
  if (typeof value !== 'number') return 0
  return Math.min(100, Math.max(0, Number((value * 100).toFixed(1))))
}

const progressDialogVisible = ref(false)
const progressLoading = ref(false)
const progressIndicator = ref<IndicatorTreeNode | null>(null)
const progressTrend = ref<IndicatorTrend | null>(null)
let progressRequestId = 0

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

async function openProgressDialog(indicator: IndicatorTreeNode): Promise<void> {
  progressIndicator.value = indicator
  progressTrend.value = null
  progressDialogVisible.value = true
  progressLoading.value = true
  const requestId = ++progressRequestId
  try {
    const result = await getIndicatorTrend(indicator.id)
    if (requestId === progressRequestId) progressTrend.value = result
  } finally {
    if (requestId === progressRequestId) progressLoading.value = false
  }
}

function trendActualText(value: number | null | undefined, caliber: StatCaliber | null): string {
  if (value === null || value === undefined) return '—'
  if (caliber === 'BOOLEAN') return value >= 1 ? '是' : '否'
  if (isPercentCaliber(caliber)) return formatPercent(value)
  return formatNumber(value)
}

function trendBarStatus(item: TrendYearItem): 'success' | undefined {
  return typeof item.completionRate === 'number' && item.completionRate >= 1 ? 'success' : undefined
}

const materialDialogVisible = ref(false)
const materialLoading = ref(false)
const materialIndicator = ref<IndicatorTreeNode | null>(null)
const materialRows = ref<MaterialItem[]>([])
const materialTotal = ref(0)
const materialPage = ref(1)
const materialPageSize = ref(10)
const materialCounts = ref<Record<string, number>>({})
let materialRequestId = 0
let materialCountRequestId = 0

const previewVisible = ref(false)
const previewFile = ref<MaterialItem | null>(null)

async function loadIndicatorMaterials(): Promise<void> {
  const indicator = materialIndicator.value
  if (!indicator) return
  const requestId = ++materialRequestId
  materialLoading.value = true
  try {
    const result = await listMaterials({
      indicatorId: indicator.id,
      page: materialPage.value,
      pageSize: materialPageSize.value,
    })
    if (requestId !== materialRequestId) return
    materialRows.value = result.items
    materialTotal.value = result.total
  } finally {
    if (requestId === materialRequestId) materialLoading.value = false
  }
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
    if (requestId === materialCountRequestId && requestedPlanId === planId.value) materialCounts.value = {}
  }
}

function materialCountOf(indicatorId: string): number {
  return materialCounts.value[indicatorId] ?? 0
}

function openMaterialDialog(indicator: IndicatorTreeNode): void {
  materialIndicator.value = indicator
  materialRows.value = []
  materialTotal.value = 0
  materialPage.value = 1
  materialDialogVisible.value = true
  void loadIndicatorMaterials()
}

function changeMaterialPageSize(): void {
  materialPage.value = 1
  void loadIndicatorMaterials()
}

function previewMaterial(row: MaterialItem): void {
  previewFile.value = row
  previewVisible.value = true
}

async function downloadMaterial(row: MaterialItem): Promise<void> {
  await downloadFile(row.id, row.fileName, row.size)
}

const aRows = computed(() => tree.value.filter((node) => node.level === 'A'))
const aConstraintCount = computed(() => aRows.value.filter((node) => node.nature === 'CONSTRAINT').length)
const aDevelopmentCount = computed(() => aRows.value.filter((node) => node.nature === 'DEVELOPMENT').length)

const drawerVisible = ref(false)
const editingIndicator = ref<Indicator | null>(null)
function openCreateA(): void {
  editingIndicator.value = null
  drawerVisible.value = true
}
function hasDistributedScope(row: IndicatorTreeNode): boolean {
  return row.status === 'DISTRIBUTED' || row.children.some((child) => child.status === 'DISTRIBUTED')
}
function canEditRow(row: IndicatorTreeNode): boolean {
  return canEdit.value && !readOnly.value && !hasDistributedScope(row)
}
function canOpenEdit(row: IndicatorTreeNode): boolean {
  return canEditRow(row) || (canAdjust.value && !readOnly.value && hasDistributedScope(row))
}
function editActionHint(row: IndicatorTreeNode): string {
  if (readOnly.value) return '计划已归档，指标及目标只读'
  if (hasDistributedScope(row)) {
    return canAdjust.value
      ? '已下发指标通过调整申请修改目标，审批通过后生效'
      : '已下发指标需通过调整申请修改目标'
  }
  return ''
}
function openEdit(row: IndicatorTreeNode): void {
  if (hasDistributedScope(row)) {
    if (!canAdjust.value || readOnly.value) return
    void router.push({ path: '/adjustments/apply', query: { indicatorId: row.id } })
    return
  }
  if (!canEditRow(row)) return
  editingIndicator.value = row
  drawerVisible.value = true
}
async function onSaved(): Promise<void> {
  const savedIndicatorId = editingIndicator.value?.id
  if (savedIndicatorId) invalidateIndicatorTasks(savedIndicatorId)
  const refreshes: Promise<unknown>[] = [loadTree(), loadStatsTree()]
  if (savedIndicatorId && expandedIds.has(savedIndicatorId)) {
    refreshes.push(ensureIndicatorTasks(savedIndicatorId).catch(() => undefined))
  }
  await Promise.all(refreshes)
}

const activeRowActionsId = ref<string | null>(null)
const rowActionsDropdownRef = ref<{ handleOpen: () => void } | null>(null)
async function openRowActions(id: string): Promise<void> {
  activeRowActionsId.value = id
  await nextTick()
  rowActionsDropdownRef.value?.handleOpen()
}
function handleRowActionsVisible(visible: boolean): void {
  if (!visible) activeRowActionsId.value = null
}
function goDetail(row: Indicator): void {
  router.push(`/indicators/${row.id}`)
}

const taskDrawerVisible = ref(false)
const taskDrawerId = ref<string | null>(null)
function openTaskDrawer(task: TaskListItem): void {
  taskDrawerId.value = task.id
  taskDrawerVisible.value = true
}
function openTaskMaterials(task: TaskListItem, year: number): void {
  const indicatorId = task.topIndicator?.id ?? task.indicator?.id
  if (!indicatorId) return
  void router.push({
    name: 'Materials',
    query: {
      planId: planId.value,
      indicatorId,
      ...(task.unit?.id ? { unitId: task.unit.id } : {}),
      taskId: task.id,
      taskCode: task.code,
      year: String(year),
      from: 'indicators',
    },
  })
}
function canAllocateRow(row: IndicatorTreeNode): boolean {
  return canAllocate.value && row.level === 'A' && row.isQuantifiable
}
const allocationButtonText = '任务分解'
const allocationVisible = ref(false)
const allocationIndicator = ref<IndicatorTreeNode | null>(null)
const indicatorMainRef = ref<HTMLElement>()
function goProgress(): void {
  router.push(`/plans/${planId.value}/indicator-progress`)
}
function goAllocation(row: IndicatorTreeNode): void {
  allocationIndicator.value = row
  allocationVisible.value = true
}
async function onAllocationSaved(indicatorId: string): Promise<void> {
  const scrollTop = indicatorMainRef.value?.scrollTop ?? 0
  invalidateIndicatorTasks(indicatorId)
  const refreshes: Promise<unknown>[] = [loadTree(), loadStatsTree()]
  if (expandedIds.has(indicatorId)) {
    refreshes.push(ensureIndicatorTasks(indicatorId).catch(() => undefined))
  }
  await Promise.all(refreshes)
  await nextTick()
  if (indicatorMainRef.value) indicatorMainRef.value.scrollTop = scrollTop
}
async function handleDeleteDirection(direction: { id: string; name: string }): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除主要建设方向「${direction.name}」？该方向下存在指标时不能删除（40008）。`,
      '确认删除',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteDirection(direction.id)
    ElMessage.success('主要建设方向已删除')
    if (selectedDirectionId.value === direction.id) selectDirection('')
    await Promise.all([loadDirections(), loadTree()])
  } catch {
    // 拦截器已提示。
  }
}

async function handleDelete(row: Indicator): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除指标「${row.code} ${row.name}」？已下发的指标不能删除（40004）。`,
      '确认删除',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteIndicator(row.id)
    ElMessage.success('指标已删除')
    await loadTree()
  } catch {
    // 拦截器已提示。
  }
}

const importVisible = ref(false)
const exporting = ref(false)
async function handleExport(): Promise<void> {
  exporting.value = true
  const notification = ElNotification({
    title: '导出 Excel',
    message: '导出任务已创建，排队中 → 生成中…',
    type: 'info',
    duration: 0,
  })
  try {
    const { exportId } = await createExport(
      { type: 'INDICATORS', params: { planId: planId.value } },
      newIdempotencyKey(),
    )
    const job = await pollExport(exportId)
    notification.close()
    if (job.status === 'SUCCEEDED' && job.downloadUrl) {
      triggerFileDownload(job.downloadUrl, '指标总表.xlsx')
      ElNotification({ title: '导出成功', message: '导出完成，已开始下载', type: 'success' })
    } else {
      ElNotification({ title: '导出失败', message: job.errorMessage ?? '导出任务失败', type: 'error' })
    }
  } catch (error) {
    notification.close()
    ElNotification({
      title: '导出失败',
      message: error instanceof Error ? error.message : '导出任务失败',
      type: 'error',
    })
  } finally {
    exporting.value = false
  }
}

async function redirectToActivePlan(): Promise<void> {
  try {
    const plans = await listPlans()
    const active = plans.find((item) => item.status === 'ACTIVE') ?? plans[0]
    if (active) await router.replace(`/plans/${active.id}/indicators`)
  } catch {
    // 拉取失败保持空状态引导。
  }
}

watch(planId, (id, previousId) => {
  planRequestId += 1
  treeRequestId += 1
  statsRequestId += 1
  directionRequestId += 1
  expandAllRequestId += 1
  materialCountRequestId += 1
  expandAllLoading.value = false
  plan.value = null
  tree.value = []
  statsTree.value = []
  yearStatsIndexes.value = new Map()
  directionList.value = []
  materialCounts.value = {}
  const switchingPlan = Boolean(previousId && previousId !== id)
  selectedDirectionId.value = switchingPlan ? '' : parseDirectionIdQuery(route.query.directionId)
  if (switchingPlan) syncDirectionQuery('')
  editMode.value = false
  for (const key of Object.keys(tasksByIndicator)) delete tasksByIndicator[key]
  taskRequests.clear()
  taskRequestGenerations.clear()
  taskLoadingIds.clear()
  taskErrorIds.clear()
  for (const timer of expandTimers.values()) clearTimeout(timer)
  expandTimers.clear()
  restoreExpandedState()
  if (id) void loadAll()
  else loading.value = false
}, { immediate: true })

watch(() => route.query.directionId, (value) => {
  const directionId = parseDirectionIdQuery(value)
  if (selectedDirectionId.value !== directionId) selectedDirectionId.value = directionId
})

watch(visibleTree, () => updateExpandAllState())

onMounted(() => {
  if (!planId.value) void redirectToActivePlan()
  getUserOptions().then((list) => {
    userOptions.value = list
  }).catch(() => undefined)
})

onBeforeUnmount(() => {
  if (keywordTimer !== null) clearTimeout(keywordTimer)
  for (const timer of expandTimers.values()) clearTimeout(timer)
  expandTimers.clear()
})

useAutoRefresh(() => loadAll(true), { skip: () => editMode.value })
</script>

<template>
  <el-empty v-if="!planId" description="请先从首页选择一个计划">
    <el-button type="primary" @click="router.push('/plans')">返回计划列表</el-button>
  </el-empty>

  <div v-else v-loading="loading" class="indicator-page">
    <div class="page-header">
      <div class="page-heading-copy">
        <span class="page-eyebrow">五年规划 · 指标体系</span>
        <h1 class="page-title">指标总表</h1>
        <p class="page-subtitle">{{ plan?.name ?? '' }}<span aria-hidden="true"> / </span>共 {{ aRows.length }} 项关键产出指标</p>
      </div>
    </div>
    <div class="app-action-row header-actions">
      <el-button v-if="canEdit && !readOnly" type="primary" @click="openCreateA">新增指标</el-button>
      <el-button v-if="canEnterEditMode" :type="editMode ? 'danger' : 'default'" plain @click="editMode = !editMode">
        {{ editMode ? '完成编辑' : '编辑' }}
      </el-button>
      <el-button @click="goProgress">进度可视化</el-button>
      <el-dropdown trigger="click" class="data-menu">
        <el-button :loading="exporting">
          数据管理<el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="canEdit && !readOnly" @click="importVisible = true">导入 Excel</el-dropdown-item>
            <el-dropdown-item :disabled="exporting" @click="handleExport">导出 Excel</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <el-alert
      v-if="readOnly"
      title="该计划已归档，只能查看，不能编辑或下发"
      type="info"
      :closable="false"
      class="page-alert"
    />

    <section class="indicator-workspace" :class="{ 'is-direction-collapsed': directionSidebarCollapsed }">
      <aside class="direction-sidebar" :class="{ 'is-collapsed': directionSidebarCollapsed }">
        <div class="direction-sidebar__header">
          <strong v-if="!directionSidebarCollapsed">主要建设方向</strong>
          <div class="direction-sidebar__header-actions">
            <el-button v-if="!directionSidebarCollapsed && isAdmin && !readOnly" link type="primary" @click="addDirection">添加</el-button>
            <el-button
              class="direction-sidebar__toggle"
              link
              type="primary"
              :aria-label="directionSidebarCollapsed ? '展开主要建设方向' : '收起主要建设方向'"
              :aria-expanded="!directionSidebarCollapsed"
              aria-controls="direction-sidebar-content"
              :title="directionSidebarCollapsed ? '展开主要建设方向' : '收起主要建设方向'"
              @click="toggleDirectionSidebar"
            >
              <el-icon><Expand v-if="directionSidebarCollapsed" /><Fold v-else /></el-icon>
            </el-button>
          </div>
        </div>
        <Transition name="direction-sidebar-content">
          <div v-if="!directionSidebarCollapsed" id="direction-sidebar-content" class="direction-sidebar__body">
            <button
              type="button"
              class="direction-nav-item direction-nav-all"
              :class="{ 'is-active': selectedDirectionId === '' }"
              :aria-pressed="selectedDirectionId === ''"
              data-direction-id=""
              @click="selectDirection('')"
            >
              <span>全部方向</span><span>{{ aRows.length }}</span>
            </button>
            <div v-for="direction in directionNavItems" :key="direction.id" class="direction-nav-row">
              <button
                type="button"
                class="direction-nav-item"
                :class="{ 'is-active': selectedDirectionId === direction.id }"
                :aria-pressed="selectedDirectionId === direction.id"
                :data-direction-id="direction.id"
                @click="selectDirection(direction.id)"
              >
                <span>{{ direction.name }}</span><span>{{ direction.count }}</span>
              </button>
              <el-button
                v-if="editMode && canDeleteDirection && direction.id !== '__UNASSIGNED__'"
                class="direction-nav-delete"
                link
                type="danger"
                :icon="Delete"
                :aria-label="`删除主要建设方向 ${direction.name}`"
                title="删除主要建设方向"
                @click.stop="handleDeleteDirection(direction)"
              />
            </div>
          </div>
        </Transition>
      </aside>

      <div ref="indicatorMainRef" class="indicator-main">
        <div class="simple-filter">
          <el-select
            v-model="selectedDirectionId"
            class="simple-filter__direction"
            placeholder="全部方向"
            filterable
            aria-label="按主要建设方向筛选"
            @change="selectDirection"
          >
            <el-option label="全部方向" value="" />
            <el-option v-for="direction in directionNavItems" :key="direction.id" :label="direction.name" :value="direction.id" />
          </el-select>
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="指标编码或名称"
            class="simple-filter__keyword"
            @input="onKeywordInput"
          />
          <el-select v-model="filters.completionStatus" placeholder="今年完成情况" class="simple-filter__status">
            <el-option label="全部指标" value="" />
            <el-option label="今年未完成目标" value="UNFINISHED" />
            <el-option label="今年已完成目标" value="FINISHED" />
          </el-select>
          <el-button type="primary" class="simple-filter__query" @click="applyFilters">查询</el-button>
          <el-popover placement="bottom-end" :width="360" trigger="click">
            <template #reference>
              <el-button link class="more-filter-button">更多筛选</el-button>
            </template>
            <el-form label-width="82px" class="advanced-filter-form">
              <el-form-item label="指标性质">
                <el-select v-model="filters.nature" clearable placeholder="全部性质" style="width: 100%">
                  <el-option :label="enumLabel('IndicatorNature', 'CONSTRAINT', userStore.enums)" value="CONSTRAINT" />
                  <el-option :label="enumLabel('IndicatorNature', 'DEVELOPMENT', userStore.enums)" value="DEVELOPMENT" />
                </el-select>
              </el-form-item>
              <el-form-item label="量化状态">
                <el-select v-model="filters.quantStatus" clearable placeholder="全部状态" style="width: 100%">
                  <el-option label="可量化" value="QUANTIFIABLE" />
                  <el-option label="不可量化" value="NON_QUANTIFIABLE" />
                </el-select>
              </el-form-item>
              <el-form-item label="负责人">
                <el-select v-model="filters.ownerId" clearable filterable placeholder="全部负责人" style="width: 100%" :disabled="filters.onlyMine" @change="applyFilters">
                  <el-option v-for="user in userOptions" :key="user.id" :label="`${user.name}（${user.username}）`" :value="user.id" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-checkbox v-model="filters.onlyMine" @change="applyFilters">只看我负责的</el-checkbox>
                <el-button link type="primary" @click="resetFilters">重置全部</el-button>
              </el-form-item>
            </el-form>
          </el-popover>
          <el-button link type="primary" class="expand-all-button" :loading="expandAllLoading" @click="toggleExpandAll">
            {{ expandAll ? '全部收起' : '全部展开' }}
          </el-button>
        </div>

        <ResponsiveRecordView class="indicator-records">
          <template #table>
            <el-table
              :data="lazyTree"
              row-key="id"
              :expand-row-keys="expandedRowKeys"
              class="simple-indicator-table"
              table-layout="fixed"
              @expand-change="handleTableExpandChange"
            >
              <el-table-column type="expand" width="36">
                <template #default="{ row }">
                  <div class="a-inline-expansion">
                    <div class="row-actions a-expansion-actions">
                      <el-button link type="primary" @click="goDetail(row.indicator)">详情</el-button>
                      <el-button link type="primary" :disabled="!canAllocateRow(row.indicator)" @click="goAllocation(row.indicator)">{{ allocationButtonText }}</el-button>
                      <el-tooltip
                        v-if="canEdit"
                        :disabled="canEditRow(row.indicator)"
                        :content="editActionHint(row.indicator)"
                        placement="top"
                      >
                        <span class="edit-action-wrapper">
                          <el-button link type="primary" :disabled="!canOpenEdit(row.indicator)" @click="openEdit(row.indicator)">
                            <el-icon><Edit /></el-icon>{{ hasDistributedScope(row.indicator) ? '调整' : '编辑' }}
                          </el-button>
                        </span>
                      </el-tooltip>
                      <el-dropdown
                        v-if="activeRowActionsId === row.id"
                        ref="rowActionsDropdownRef"
                        trigger="click"
                        @visible-change="handleRowActionsVisible"
                      >
                        <el-button link type="primary">更多<el-icon :size="12"><ArrowDown /></el-icon></el-button>
                        <template #dropdown>
                          <el-dropdown-menu>
                            <el-dropdown-item :disabled="!canEditRow(row.indicator)" class="danger-item" @click="handleDelete(row.indicator)"><span class="danger-text">删除</span></el-dropdown-item>
                          </el-dropdown-menu>
                        </template>
                      </el-dropdown>
                      <el-button v-else link type="primary" @click="openRowActions(row.id)">更多<el-icon :size="12"><ArrowDown /></el-icon></el-button>
                    </div>
                    <div class="a-quant-summary">
                      <div><span>统计口径</span><StatusTag v-if="row.indicator.statCaliber" kind="StatCaliber" :value="row.indicator.statCaliber" /><template v-else>—</template></div>
                      <div>
                        <span>五年目标</span>
                        <button
                          v-if="row.indicator.isQuantifiable && canOpenEdit(row.indicator)"
                          type="button"
                          class="editable-value"
                          :aria-label="`编辑 ${row.indicator.code} 的五年目标和各年度目标`"
                          title="点击维护目标；已下发指标将进入调整申请"
                          @click="openEdit(row.indicator)"
                        >
                          <strong>{{ totalTargetDisplay(row.indicator) }}</strong>
                        </button>
                        <strong v-else>{{ totalTargetDisplay(row.indicator) }}</strong>
                        <RollupBadge v-if="row.indicator.rollupMode" :mode="row.indicator.rollupMode" />
                      </div>
                      <div><span>当前完成</span><strong :class="completionLevelClass(aCurrentLevel(row.id))">{{ aStats(row.id)?.actualValue ?? '—' }}</strong></div>
                      <div><span>当年完成率</span><strong :class="completionLevelClass(aCurrentLevel(row.id))">{{ formatPercent(aStats(row.id)?.completionRate) }}</strong></div>
                      <div><span>五年累计</span><strong :class="completionLevelClass(aCumulativeLevel(row.indicator))">{{ formatPercent(aStats(row.id)?.cumulativeRate) }}</strong></div>
                      <div><span>负责人</span><strong>{{ row.indicator.owner?.name ?? '—' }}</strong></div>
                    </div>
                    <div class="a-summary-years">
                      <div v-for="year in years" :key="year" class="year-target-cell">
                        <div class="year-target-head">
                          <span :class="{ 'current-year': year === currentYear }">{{ year }}</span>
                          <span class="year-target-label">
                            目标
                            <button
                              v-if="row.indicator.isQuantifiable && canOpenEdit(row.indicator)"
                              type="button"
                              class="editable-value year-target-value"
                              :aria-label="`编辑 ${row.indicator.code} 的 ${year} 年目标`"
                              :title="`点击维护 ${year} 年目标；已下发指标将进入调整申请`"
                              @click="openEdit(row.indicator)"
                            >
                              <strong>{{ formatTargetValue(targetOf(row.indicator, year), row.indicator.statCaliber) }}</strong>
                            </button>
                            <strong v-else>{{ formatTargetValue(targetOf(row.indicator, year), row.indicator.statCaliber) }}</strong>
                          </span>
                        </div>
                        <div class="year-actual-line">
                          <span>完成 <strong :class="completionLevelClass(yearCompletionLevel(row.indicator, year))">{{ yearActualDisplay(row.indicator, year) }}</strong></span>
                          <span v-if="yearOverDisplay(row.indicator, year)" class="year-over">{{ yearOverDisplay(row.indicator, year) }}</span>
                        </div>
                        <div
                          class="year-target-track"
                          role="progressbar"
                          :aria-label="`${year}年完成率`"
                          :aria-valuenow="yearCompletionPercent(row.indicator, year)"
                          aria-valuemin="0"
                          aria-valuemax="100"
                        >
                          <div
                            class="year-target-fill"
                            :class="completionLevelClass(yearCompletionLevel(row.indicator, year))"
                            :style="{ width: `${yearCompletionPercent(row.indicator, year)}%` }"
                          ></div>
                        </div>
                      </div>
                    </div>
                    <IndicatorTaskPanel
                      :indicator="row.indicator"
                      :tasks="tasksByIndicator[row.id]"
                      :loading="taskLoadingIds.has(row.id)"
                      :load-error="taskErrorIds.has(row.id)"
                      :years="years"
                      :stats-by-task="statsByTask"
                      :can-allocate="canAllocateRow(row.indicator) && !readOnly"
                      @retry="retryTasks(row.id)"
                      @detail="openTaskDrawer"
                      @materials="openTaskMaterials"
                    />
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="编码" width="90" class-name="mono">
                <template #default="{ row }">
                  <button
                    type="button"
                    class="row-code-expand"
                    :aria-label="`${row.indicator.code}：${expandedIds.has(row.id) ? '收起' : '展开'} 学院任务`"
                    :aria-expanded="expandedIds.has(row.id)"
                    :aria-busy="taskLoadingIds.has(row.id)"
                    :data-indicator-id="row.id"
                    @click="toggleExpansion(row)"
                  >
                    {{ row.indicator.code }}
                  </button>
                </template>
              </el-table-column>
              <el-table-column label="关键产出指标" min-width="260" class-name="complete-text-cell">
                <template #default="{ row }">
                  <div class="a-name-row">
                    <el-tooltip :content="row.indicator.name" placement="top" :show-after="200">
                      <button
                        type="button"
                        class="a-name-button"
                        :title="row.indicator.name"
                        @click="toggleExpansion(row)"
                      >
                        {{ indicatorNameDisplay(row.indicator.name) }}
                      </button>
                    </el-tooltip>
                    <el-button
                      v-if="editMode && canEditRow(row.indicator)"
                      class="a-name-delete"
                      link
                      type="danger"
                      :icon="Delete"
                      :aria-label="`删除指标 ${row.indicator.code} ${row.indicator.name}`"
                      title="删除指标"
                      @click.stop="handleDelete(row.indicator)"
                    />
                  </div>
                  <div v-if="!row.indicator.isQuantifiable" class="undecomposable-tip">不可量化 · 仅维护基础信息</div>
                  <div v-if="taskErrorIds.has(row.id) && !taskLoadingIds.has(row.id)" class="task-error-state" role="alert">学院任务加载失败，请点击编号重试</div>
                </template>
              </el-table-column>
              <el-table-column label="总进度" min-width="220" align="center" header-align="center">
                <template #default="{ row }">
                  <div
                    v-if="row.indicator.isQuantifiable"
                    class="a-total-progress"
                    role="button"
                    tabindex="0"
                    :title="`五年累计完成率 ${aTotalText(row.indicator)}；点击查看五年详细进度`"
                    :aria-label="`查看 ${row.indicator.name} 的五年详细进度`"
                    @click="openProgressDialog(row.indicator)"
                    @keydown.enter="openProgressDialog(row.indicator)"
                    @keydown.space.prevent="openProgressDialog(row.indicator)"
                  >
                    <div
                      class="year-target-track a-total-progress__track"
                      role="progressbar"
                      :aria-label="`${row.indicator.code}五年累计完成率`"
                      :aria-valuenow="aTotalPercent(row.indicator)"
                      aria-valuemin="0"
                      aria-valuemax="100"
                    >
                      <div
                        class="year-target-fill"
                        :class="completionLevelClass(aCumulativeLevel(row.indicator))"
                        :style="{ width: `${aTotalPercent(row.indicator)}%` }"
                      ></div>
                    </div>
                    <span class="a-total-progress__text" :class="completionLevelClass(aCumulativeLevel(row.indicator))">
                      {{ aTotalText(row.indicator) }}
                    </span>
                  </div>
                  <span v-else class="a-progress-empty">—</span>
                </template>
              </el-table-column>
              <el-table-column label="材料" width="110" align="center" header-align="center">
                <template #default="{ row }">
                  <el-button
                    v-if="row.indicator.isQuantifiable"
                    link
                    type="primary"
                    class="material-count"
                    :title="`查看佐证材料，共 ${formatCount(materialCountOf(row.indicator.id))} 份`"
                    @click.stop="openMaterialDialog(row.indicator)"
                  >
                    共 {{ formatCount(materialCountOf(row.indicator.id)) }} 份
                  </el-button>
                  <span v-else class="a-progress-empty">—</span>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty :image-size="80" description="当前方向或筛选条件下没有指标" />
              </template>
            </el-table>
          </template>

          <template #cards>
            <div class="responsive-card-list indicator-card-list">
              <article v-for="node in lazyTree" :key="node.id" class="responsive-card indicator-card">
                <div class="indicator-card-topline">
                  <button
                    type="button"
                    class="indicator-card-code mono indicator-card-code-button"
                    :aria-label="`${node.indicator.code}：${expandedIds.has(node.id) ? '收起' : '展开'} 学院任务`"
                    :aria-expanded="expandedIds.has(node.id)"
                    :aria-busy="taskLoadingIds.has(node.id)"
                    :data-indicator-id="node.id"
                    @click="toggleExpansion(node)"
                  >
                    {{ expandedIds.has(node.id) ? '▾' : '▸' }} {{ node.indicator.code }}
                  </button>
                  <StatusTag kind="IndicatorStatus" :value="node.indicator.status" />
                </div>
                <div class="a-name-row">
                  <el-tooltip :content="node.indicator.name" placement="top" :show-after="200">
                    <button
                      type="button"
                      class="a-name-button responsive-card__title"
                      :title="node.indicator.name"
                      @click="toggleExpansion(node)"
                    >
                      {{ indicatorNameDisplay(node.indicator.name) }}
                    </button>
                  </el-tooltip>
                  <el-button
                    v-if="editMode && canEditRow(node.indicator)"
                    class="a-name-delete"
                    link
                    type="danger"
                    :icon="Delete"
                    :aria-label="`删除指标 ${node.indicator.code} ${node.indicator.name}`"
                    title="删除指标"
                    @click.stop="handleDelete(node.indicator)"
                  />
                </div>
                <div
                  class="a-total-progress a-total-progress--card"
                  :title="`五年累计完成率 ${aTotalText(node.indicator)}`"
                >
                  <span class="a-total-progress__label">总进度</span>
                  <div
                    class="year-target-track a-total-progress__track"
                    role="progressbar"
                    :aria-label="`${node.indicator.code}五年累计完成率`"
                    :aria-valuenow="aTotalPercent(node.indicator)"
                    aria-valuemin="0"
                    aria-valuemax="100"
                  >
                    <div
                      class="year-target-fill"
                      :class="completionLevelClass(aCumulativeLevel(node.indicator))"
                      :style="{ width: `${aTotalPercent(node.indicator)}%` }"
                    ></div>
                  </div>
                  <span class="a-total-progress__text" :class="completionLevelClass(aCumulativeLevel(node.indicator))">
                    {{ aTotalText(node.indicator) }}
                  </span>
                </div>
                <div v-if="!node.indicator.isQuantifiable" class="undecomposable-tip">不可量化 · 仅维护基础信息</div>
                <div v-if="taskErrorIds.has(node.id) && !taskLoadingIds.has(node.id)" class="task-error-state" role="alert">学院任务加载失败，请点击编号重试</div>
                <el-button
                  link
                  type="primary"
                  class="indicator-card-expand"
                  :loading="taskLoadingIds.has(node.id)"
                  @click="toggleExpansion(node)"
                >
                  {{ expandedIds.has(node.id) ? '收起学院 B 与量化详情' : '展开学院 B 与量化详情' }}
                  <el-icon class="expand-icon" :class="{ expanded: expandedIds.has(node.id) }"><ArrowDown /></el-icon>
                </el-button>

                <div v-if="expandedIds.has(node.id)" class="a-inline-expansion">
                  <div class="row-actions a-expansion-actions">
                    <el-button link type="primary" @click="goDetail(node.indicator)">详情</el-button>
                    <el-button link type="primary" :disabled="!canAllocateRow(node.indicator)" @click="goAllocation(node.indicator)">{{ allocationButtonText }}</el-button>
                    <el-tooltip
                      v-if="canEdit"
                      :disabled="canEditRow(node.indicator)"
                      :content="editActionHint(node.indicator)"
                      placement="top"
                    >
                      <span class="edit-action-wrapper">
                        <el-button link type="primary" :disabled="!canOpenEdit(node.indicator)" @click="openEdit(node.indicator)">
                          <el-icon><Edit /></el-icon>{{ hasDistributedScope(node.indicator) ? '调整' : '编辑' }}
                        </el-button>
                      </span>
                    </el-tooltip>
                    <el-dropdown
                      v-if="activeRowActionsId === node.id"
                      ref="rowActionsDropdownRef"
                      trigger="click"
                      @visible-change="handleRowActionsVisible"
                    >
                      <el-button link type="primary">更多<el-icon :size="12"><ArrowDown /></el-icon></el-button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item :disabled="!canEditRow(node.indicator)" class="danger-item" @click="handleDelete(node.indicator)"><span class="danger-text">删除</span></el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                    <el-button v-else link type="primary" @click="openRowActions(node.id)">更多<el-icon :size="12"><ArrowDown /></el-icon></el-button>
                  </div>

                  <div class="a-quant-summary">
                    <div><span>统计口径</span><StatusTag v-if="node.indicator.statCaliber" kind="StatCaliber" :value="node.indicator.statCaliber" /><template v-else>—</template></div>
                    <div>
                      <span>五年目标</span>
                      <button
                        v-if="node.indicator.isQuantifiable && canOpenEdit(node.indicator)"
                        type="button"
                        class="editable-value"
                        :aria-label="`编辑 ${node.indicator.code} 的五年目标和各年度目标`"
                        title="点击维护目标；已下发指标将进入调整申请"
                        @click="openEdit(node.indicator)"
                      >
                        <strong>{{ totalTargetDisplay(node.indicator) }}</strong>
                      </button>
                      <strong v-else>{{ totalTargetDisplay(node.indicator) }}</strong>
                    </div>
                    <div><span>当前完成</span><strong :class="completionLevelClass(aCurrentLevel(node.id))">{{ aStats(node.id)?.actualValue ?? '—' }}</strong></div>
                    <div><span>当年完成率</span><strong :class="completionLevelClass(aCurrentLevel(node.id))">{{ formatPercent(aStats(node.id)?.completionRate) }}</strong></div>
                    <div><span>五年累计</span><strong :class="completionLevelClass(aCumulativeLevel(node.indicator))">{{ formatPercent(aStats(node.id)?.cumulativeRate) }}</strong></div>
                    <div><span>负责人</span><strong>{{ node.indicator.owner?.name ?? '—' }}</strong></div>
                  </div>
                  <div class="a-summary-years">
                    <div v-for="year in years" :key="year" class="year-target-cell">
                      <div class="year-target-head">
                        <span :class="{ 'current-year': year === currentYear }">{{ year }}</span>
                        <span class="year-target-label">
                          目标
                          <button
                            v-if="node.indicator.isQuantifiable && canOpenEdit(node.indicator)"
                            type="button"
                            class="editable-value year-target-value"
                            :aria-label="`编辑 ${node.indicator.code} 的 ${year} 年目标`"
                            :title="`点击维护 ${year} 年目标；已下发指标将进入调整申请`"
                            @click="openEdit(node.indicator)"
                          >
                            <strong>{{ formatTargetValue(targetOf(node.indicator, year), node.indicator.statCaliber) }}</strong>
                          </button>
                          <strong v-else>{{ formatTargetValue(targetOf(node.indicator, year), node.indicator.statCaliber) }}</strong>
                        </span>
                      </div>
                      <div class="year-actual-line">
                        <span>完成 <strong :class="completionLevelClass(yearCompletionLevel(node.indicator, year))">{{ yearActualDisplay(node.indicator, year) }}</strong></span>
                        <span v-if="yearOverDisplay(node.indicator, year)" class="year-over">{{ yearOverDisplay(node.indicator, year) }}</span>
                      </div>
                      <div
                        class="year-target-track"
                        role="progressbar"
                        :aria-label="`${year}年完成率`"
                        :aria-valuenow="yearCompletionPercent(node.indicator, year)"
                        aria-valuemin="0"
                        aria-valuemax="100"
                      >
                        <div
                          class="year-target-fill"
                          :class="completionLevelClass(yearCompletionLevel(node.indicator, year))"
                          :style="{ width: `${yearCompletionPercent(node.indicator, year)}%` }"
                        ></div>
                      </div>
                    </div>
                  </div>
                  <IndicatorTaskPanel
                    :indicator="node.indicator"
                    :tasks="tasksByIndicator[node.id]"
                    :loading="taskLoadingIds.has(node.id)"
                    :load-error="taskErrorIds.has(node.id)"
                    :years="years"
                    :stats-by-task="statsByTask"
                    :can-allocate="canAllocateRow(node.indicator) && !readOnly"
                    @retry="retryTasks(node.id)"
                    @detail="openTaskDrawer"
                    @materials="openTaskMaterials"
                  />
                </div>
              </article>
              <el-empty v-if="lazyTree.length === 0" :image-size="80" description="当前方向或筛选条件下没有指标" />
            </div>
          </template>
        </ResponsiveRecordView>
        <div class="table-footer">共 {{ aRows.length }} 条指标；当前显示 {{ lazyTree.length }} 条；约束性 {{ aConstraintCount }} / 发展性 {{ aDevelopmentCount }}</div>
      </div>
    </section>

    <IndicatorEditDrawer
      v-model="drawerVisible"
      :plan-id="planId"
      :indicator="editingIndicator"
      :years="years"
      :direction-options="directionOptions"
      :plan-archived="isArchived"
      @saved="onSaved"
    />
    <TaskAllocationModal
      v-model="allocationVisible"
      :plan-id="planId"
      :indicator-id="allocationIndicator?.id ?? null"
      :indicator-code="allocationIndicator?.code"
      :indicator-name="allocationIndicator?.name"
      @changed="onAllocationSaved"
    />
    <TaskDetailDrawer
      v-model="taskDrawerVisible"
      :task-id="taskDrawerId"
    />
    <ImportDialog v-model="importVisible" :plan-id="planId" @imported="loadTree" />

    <el-dialog v-model="progressDialogVisible" width="760px" align-center class="indicator-progress-dialog">
      <template #header>
        <div class="dialog-heading">
          <h3>五年详细进度</h3>
          <p><span>{{ progressIndicator?.code }}</span>{{ progressIndicator?.name }}</p>
        </div>
      </template>
      <div v-loading="progressLoading" class="progress-dialog-content">
        <div class="progress-dialog-summary">
          <div><span>五年完成率</span><strong class="is-primary">{{ formatPercent(progressSummary.rate) }}</strong></div>
          <div><span>需提交数量</span><strong>{{ formatCount(progressSummary.requiredCount) }}</strong></div>
          <div><span>达标数量</span><strong class="is-success">{{ formatCount(progressSummary.achievedCount) }}</strong></div>
          <div><span>进行中数量</span><strong>{{ formatCount(progressSummary.inProgressCount) }}</strong></div>
        </div>
        <div class="five-year-progress">
          <div class="progress-section-title">五年累计进度</div>
          <el-progress
            :percentage="progressPercentage(progressSummary.rate)"
            :status="(progressSummary.rate ?? 0) >= 1 ? 'success' : undefined"
            :stroke-width="20"
            :format="() => formatPercent(progressSummary.rate)"
          />
        </div>
        <div v-if="progressTrend" class="progress-year-list">
          <div class="progress-section-title">逐年完成进度</div>
          <div v-for="yearItem in progressTrend.years" :key="yearItem.year" class="progress-year-row">
            <span class="progress-year-label">{{ yearItem.year }}</span>
            <el-progress
              :percentage="progressPercentage(yearItem.completionRate)"
              :status="trendBarStatus(yearItem)"
              :stroke-width="16"
              :format="() => formatPercent(yearItem.completionRate)"
            />
            <span class="progress-year-values">
              {{ trendActualText(yearItem.actualValue, progressTrend.statCaliber) }} / {{ formatTargetValue(yearItem.target, progressTrend.statCaliber) }}
            </span>
          </div>
        </div>
        <el-empty v-else-if="!progressLoading" :image-size="70" description="暂无逐年进度数据" />
      </div>
    </el-dialog>

    <el-dialog v-model="materialDialogVisible" width="1000px" align-center class="indicator-material-dialog">
      <template #header>
        <div class="dialog-heading">
          <h3>佐证材料</h3>
          <p><span>{{ materialIndicator?.code }}</span>{{ materialIndicator?.name }}</p>
        </div>
      </template>
      <el-table v-loading="materialLoading" :data="materialRows" border height="420">
        <el-table-column label="材料名称" min-width="240">
          <template #default="{ row }"><span class="material-file-name" :title="row.fileName">{{ row.fileName }}</span></template>
        </el-table-column>
        <el-table-column label="所属学院" min-width="150">
          <template #default="{ row }">{{ row.unit?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="大小" width="100" align="right">
          <template #default="{ row }">{{ formatFileSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="上传人" width="110">
          <template #default="{ row }">{{ row.uploadedBy?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="155">
          <template #default="{ row }">{{ formatDateTime(row.uploadedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="125" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="previewMaterial(row)">预览</el-button>
            <el-button link type="primary" @click="downloadMaterial(row)">下载</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty :image-size="80" description="该指标暂无已提交的佐证材料" /></template>
      </el-table>
      <el-pagination
        v-model:current-page="materialPage"
        v-model:page-size="materialPageSize"
        class="material-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :total="materialTotal"
        :page-sizes="[10, 20, 50]"
        @current-change="loadIndicatorMaterials"
        @size-change="changeMaterialPageSize"
      />
    </el-dialog>
    <FilePreviewDrawer v-model="previewVisible" :file="previewFile" />
  </div>
</template>

<style scoped>
.indicator-page {
  --indicator-ink: #17233c;
  --indicator-muted: #6c7687;
  --indicator-line: #e4e9f0;
  --indicator-warm: #f6f8fb;
  --indicator-paper: #ffffff;
  --indicator-soft: #f8fafc;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  color: var(--indicator-ink);
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: 18px;
  padding: 2px 0 18px;
  border-bottom: 1px solid var(--indicator-line);
}
.page-heading-copy { min-width: 0; }
.page-eyebrow {
  display: block;
  margin-bottom: 5px;
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1.5px;
  line-height: 16px;
}
.page-title { margin: 0; font-size: 25px; line-height: 34px; font-weight: 650; letter-spacing: -0.4px; }
.page-subtitle { margin: 4px 0 0; color: var(--indicator-muted); font-size: 12px; line-height: 20px; }
.page-subtitle > span { padding: 0 7px; color: var(--el-border-color); }
.header-actions { display: flex; flex-wrap: wrap; justify-content: flex-start; gap: var(--space-2); }
.header-actions :deep(.el-button) { margin-left: 0; }
.data-menu { flex: 0 0 auto; }
.page-alert { margin-bottom: var(--space-4); }

.indicator-workspace {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  flex: 1;
  gap: 22px;
  transition: grid-template-columns 0.2s ease;
  min-height: 0;
  overflow: hidden;
  min-width: 0;
  background: transparent;
}
.indicator-workspace.is-direction-collapsed { grid-template-columns: 44px minmax(0, 1fr); }
.direction-sidebar {
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 10px;
  border: 1px solid var(--indicator-line);
  border-top: 3px solid var(--el-color-primary);
  border-radius: 8px;
  background: var(--indicator-paper);
}
.direction-sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  padding: 0 10px 14px;
  border-bottom: 1px solid var(--indicator-line);
  font-size: 14px;
}
.direction-sidebar__header strong { font-weight: 600; letter-spacing: 0.3px; }
.direction-sidebar__header-actions { display: flex; align-items: center; gap: 2px; }
.direction-sidebar__header-actions :deep(.el-button) { margin-left: 0; }
.direction-sidebar__toggle { width: 30px; height: 30px; padding: 0; }
.direction-sidebar.is-collapsed { overflow: hidden; padding: 8px 4px; }
.direction-sidebar.is-collapsed .direction-sidebar__header { justify-content: center; margin: 0; padding: 0; border-bottom: 0; }
.direction-sidebar__body { transform-origin: left top; }
.direction-sidebar-content-enter-active, .direction-sidebar-content-leave-active { transition: opacity 0.16s ease, transform 0.18s ease; }
.direction-sidebar-content-enter-from, .direction-sidebar-content-leave-to { opacity: 0; transform: translateX(-8px); }
.direction-nav-item {
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
.direction-nav-item > span:first-child { min-width: 0; }
.direction-nav-item > span:last-child {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 20px;
  padding: 0 5px;
  border-radius: 10px;
  background: var(--indicator-warm);
  color: var(--indicator-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.direction-nav-item:hover { background: var(--indicator-warm); color: var(--el-color-primary); }
.direction-nav-item.is-active { background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; }
.direction-nav-item.is-active::before { position: absolute; top: 10px; bottom: 10px; left: 0; width: 3px; border-radius: 2px; background: var(--el-color-primary); content: ''; }
.direction-nav-item.is-active > span:last-child { background: var(--el-color-primary); color: #fff; }
.direction-nav-item:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.direction-nav-all { margin-bottom: 6px; }
.direction-nav-row { display: flex; align-items: center; gap: 2px; }
.direction-nav-row .direction-nav-item { flex: 1 1 auto; min-width: 0; }
.direction-nav-delete { flex: 0 0 auto; margin-right: 4px; }

.indicator-main { min-width: 0; min-height: 0; overflow-y: auto; padding: 0 2px 12px 0; }
.simple-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid var(--indicator-line);
  border-radius: 7px;
  background: var(--indicator-soft);
}
.simple-filter__direction { width: 210px; flex: 0 0 auto; }
.simple-filter__keyword { width: 250px; }
.simple-filter__status { width: 168px; }
.simple-filter__query { min-width: 66px; }
.more-filter-button, .expand-all-button { margin-left: 0; }
.advanced-filter-form :deep(.el-form-item) { margin-bottom: 14px; }
.advanced-filter-form :deep(.el-form-item:last-child) { margin-bottom: 0; }

.simple-indicator-table { width: 100%; color: var(--indicator-ink); }
.simple-indicator-table :deep(.el-table__inner-wrapper::before),
.simple-indicator-table :deep(.el-table__border-left-patch) { display: none; }
.simple-indicator-table :deep(.el-table__header th) {
  height: 40px;
  padding: 0;
  border-bottom: 1px solid #cfd7e3;
  background: transparent;
  color: var(--indicator-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.4px;
}
.simple-indicator-table :deep(.el-table__body td) {
  padding: 15px 0;
  border-bottom: 1px solid var(--indicator-line);
  background: transparent;
}
.simple-indicator-table :deep(.el-table__body tr:hover > td) { background: #fafbfc; }
.simple-indicator-table :deep(.el-table__expand-icon) { color: var(--indicator-muted); }
.simple-indicator-table :deep(.el-table__expand-icon:hover) { color: var(--el-color-primary); }
.simple-indicator-table :deep(.el-table__expanded-cell) { padding: 0 0 16px 36px; background: transparent; }
.simple-indicator-table :deep(.cell) { white-space: normal; overflow-wrap: anywhere; line-height: 21px; }
.mono { font-family: var(--app-font-mono); font-variant-numeric: tabular-nums; }
.complete-text-cell :deep(.cell) { word-break: normal; }
.row-code-expand, .a-name-button, .indicator-card-code-button {
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.row-code-expand {
  padding: 3px 7px;
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 600;
}
.row-code-expand:hover { background: var(--el-color-primary-light-8); }
.row-code-expand:hover, .a-name-button:hover, .indicator-card-code-button:hover { color: var(--el-color-primary); }
.row-code-expand:focus-visible, .a-name-button:focus-visible, .indicator-card-code-button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.a-name-button { display: block; max-width: 100%; overflow: hidden; color: var(--indicator-ink); font-weight: 550; line-height: 22px; text-overflow: ellipsis; white-space: nowrap; }
.a-name-row { display: flex; align-items: center; gap: 4px; min-width: 0; }
.a-name-row .a-name-button { flex: 1 1 auto; min-width: 0; }
.a-name-delete { flex: 0 0 auto; }
.material-count { font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums; }
.a-total-progress { display: flex; align-items: center; justify-content: center; gap: 8px; width: fit-content; max-width: 100%; min-width: 0; margin: 0 auto; cursor: pointer; }
.a-total-progress:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.a-total-progress--card { justify-content: flex-start; width: 100%; margin-top: 8px; cursor: default; }
.a-total-progress.a-total-progress--card .a-total-progress__track { flex: 1 1 auto; width: auto; max-width: none; height: 8px; }
.a-total-progress__label { flex: none; color: var(--indicator-muted); font-size: 12px; }
.a-total-progress .a-total-progress__track { flex: 0 0 120px; width: 120px; min-width: 0; max-width: 120px; height: 10px; border-radius: 5px; }
.a-total-progress__text { flex: none; min-width: 48px; color: var(--indicator-muted); font-size: 13px; text-align: right; font-variant-numeric: tabular-nums; }
.a-total-progress__text.completion-value--none { color: var(--el-color-danger); }
.a-total-progress__text.completion-value--partial { color: var(--el-color-warning); }
.a-total-progress__text.completion-value--complete { color: var(--el-color-success); }
.a-progress-empty { color: var(--indicator-muted); }
.dialog-heading h3 { margin: 0; color: var(--indicator-ink); font-size: 18px; line-height: 26px; }
.dialog-heading p { margin: 5px 24px 0 0; color: var(--indicator-muted); font-size: 13px; line-height: 20px; }
.dialog-heading p span { margin-right: 8px; color: var(--el-color-primary); font-weight: 650; font-variant-numeric: tabular-nums; }
.progress-dialog-content { min-height: 220px; }
.progress-dialog-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 22px; }
.progress-dialog-summary > div { padding: 13px 14px; background: var(--indicator-soft); border: 1px solid var(--indicator-line); border-radius: 6px; }
.progress-dialog-summary span { display: block; color: var(--indicator-muted); font-size: 12px; }
.progress-dialog-summary strong { display: block; margin-top: 5px; font-size: 23px; line-height: 30px; font-variant-numeric: tabular-nums; }
.progress-dialog-summary strong.is-primary { color: var(--el-color-primary); }
.progress-dialog-summary strong.is-success { color: var(--el-color-success); }
.five-year-progress { padding: 15px 16px; margin-bottom: 20px; background: var(--el-color-primary-light-9); border-radius: 6px; }
.progress-section-title { margin-bottom: 12px; color: var(--indicator-ink); font-size: 13px; font-weight: 600; }
.progress-year-list { display: flex; flex-direction: column; gap: 13px; }
.progress-year-list .progress-section-title { margin-bottom: 0; }
.progress-year-row { display: grid; grid-template-columns: 46px minmax(0, 1fr) 150px; align-items: center; gap: 12px; }
.progress-year-label { color: var(--indicator-muted); font-size: 13px; font-variant-numeric: tabular-nums; }
.progress-year-values { text-align: right; color: var(--indicator-muted); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.material-file-name { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.material-pagination { justify-content: flex-end; margin-top: 16px; }
.undecomposable-tip, .task-error-state { margin-top: 3px; font-size: 12px; }
.undecomposable-tip { color: var(--indicator-muted); }
.task-error-state { color: var(--el-color-danger); }
.row-actions { display: flex; align-items: center; justify-content: flex-start; gap: 2px 8px; flex-wrap: wrap; }
.row-actions :deep(.el-button) { margin: 0; padding: 0; }
.edit-action-wrapper { display: inline-flex; }
.a-expansion-actions { justify-content: flex-start; margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--indicator-line); }
.danger-text { color: var(--el-color-danger); }
.editable-value {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 0 2px;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: var(--el-color-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.editable-value strong {
  color: inherit !important;
  text-decoration: underline dotted;
  text-underline-offset: 3px;
}
.editable-value:hover { background: var(--el-color-primary-light-9); }
.editable-value:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.year-target-value { margin-left: 2px; }

.a-inline-expansion {
  min-width: 0;
  padding: 12px 16px 16px;
  border-left: 3px solid var(--el-color-primary);
  border-radius: 0 6px 6px 0;
  background: var(--indicator-soft);
}
.a-quant-summary {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  overflow: hidden;
  border-top: 1px solid var(--indicator-line);
  border-bottom: 1px solid var(--indicator-line);
  background: var(--indicator-paper);
}
.a-quant-summary > div {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  min-width: 0;
  padding: 12px;
  border-right: 1px solid var(--indicator-line);
}
.a-quant-summary > div:last-child { border-right: 0; }
.a-quant-summary span { color: var(--indicator-muted); font-size: 11px; }
.a-quant-summary strong { color: var(--indicator-ink); font-size: 14px; font-variant-numeric: tabular-nums; }
.a-summary-years {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin: 12px 0 4px;
}
.a-summary-years > div { display: flex; flex-direction: column; align-items: stretch; gap: 7px; padding: 9px 11px; border: 1px solid var(--indicator-line); border-radius: 5px; background: var(--indicator-paper); }
.year-target-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.a-summary-years span { color: var(--indicator-muted); font-size: 11px; }
.a-summary-years strong { font-size: 13px; font-variant-numeric: tabular-nums; }
.year-target-label { white-space: nowrap; }
.year-target-label strong { color: var(--indicator-ink); font-size: 12px; }
.year-actual-line { display: flex; align-items: center; justify-content: space-between; gap: 6px; min-width: 0; }
.year-actual-line > span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.year-actual-line strong { color: var(--indicator-ink); font-size: 12px; }
.year-over { color: var(--el-color-success); font-weight: 600; }
.year-target-track { height: 4px; border-radius: 2px; overflow: hidden; background: var(--el-fill-color-light); }
.year-target-fill { height: 100%; border-radius: inherit; background: var(--el-color-primary); transition: width 0.2s ease; }
.year-target-fill.completion-value--none { background: var(--el-color-danger); }
.year-target-fill.completion-value--partial { background: var(--el-color-warning); }
.year-target-fill.completion-value--complete { background: var(--el-color-success); }
.a-quant-summary strong.completion-value--none,
.a-summary-years strong.completion-value--none,
.year-actual-line strong.completion-value--none { color: var(--el-color-danger); }
.a-quant-summary strong.completion-value--partial,
.a-summary-years strong.completion-value--partial,
.year-actual-line strong.completion-value--partial { color: var(--el-color-warning); }
.a-quant-summary strong.completion-value--complete,
.a-summary-years strong.completion-value--complete,
.year-actual-line strong.completion-value--complete { color: var(--el-color-success); }
.current-year { color: var(--el-color-primary) !important; font-weight: 600; }
.completion-cell { display: flex; align-items: center; gap: 8px; }

.indicator-card-list { gap: 12px; }
.indicator-card { padding: 15px; border: 1px solid var(--indicator-line); border-left: 3px solid var(--el-color-primary-light-7); border-radius: 6px; background: var(--indicator-paper); box-shadow: none; }
.indicator-card-topline { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.indicator-card-code { padding: 3px 7px; border-radius: 4px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-size: 12px; }
.indicator-card-expand { justify-content: flex-start; width: 100%; margin: 10px 0 0; }
.expand-icon { margin-left: 3px; transition: transform 0.18s; }
.expand-icon.expanded { transform: rotate(180deg); }
.indicator-card .a-inline-expansion { margin-top: 12px; padding: 12px; }
.indicator-card .a-quant-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.indicator-card .a-quant-summary > div:nth-child(2n) { border-right: 0; }
.indicator-card-secondary-actions { display: flex; justify-content: flex-start; gap: 12px; }
.table-footer { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--indicator-line); color: var(--indicator-muted); font-size: 12px; line-height: 20px; }

@media (max-width: 1100px) {
  .indicator-workspace { grid-template-columns: 200px minmax(0, 1fr); gap: 16px; }
  .a-quant-summary { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .a-quant-summary > div:nth-child(3n) { border-right: 0; }
  .simple-filter { flex-wrap: wrap; }
  .simple-filter__keyword { flex: 1 1 220px; }
  .more-filter-button { margin-left: 0; }
  .expand-all-button { margin-left: 0; }
}

@media (max-width: 768px) {
  .indicator-page { display: block; height: auto; }
  .page-header { align-items: flex-start; flex-direction: column; }
  .page-title { font-size: 23px; }
  .header-actions { justify-content: flex-start; }
  .header-actions > .el-button, .data-menu { grid-column: 1 / -1; width: 100%; }
  .header-actions > .el-button, .data-menu :deep(.el-button) { min-height: 44px; }
  .data-menu :deep(.el-button) { width: 100%; min-width: 0; }
  .indicator-workspace { display: block; overflow: visible; background: transparent; }
  .direction-sidebar { display: flex; gap: 8px; margin-bottom: 14px; padding: 10px; overflow-x: auto; border: 1px solid var(--indicator-line); border-top: 3px solid var(--el-color-primary); border-radius: 7px; }
  .direction-sidebar__header { flex: 0 0 auto; align-items: flex-start; flex-direction: column; gap: 2px; margin: 0; padding: 4px 8px; border-bottom: 0; }
  .direction-sidebar__body { display: flex; flex: 1 0 auto; align-items: center; gap: 8px; min-width: max-content; }
  .direction-sidebar.is-collapsed { width: 100%; padding: 6px 10px; }
  .direction-sidebar.is-collapsed .direction-sidebar__header { align-items: center; width: 100%; padding: 0; }
  .direction-nav-item { flex: 0 0 auto; width: auto; min-height: 40px; white-space: nowrap; }
  .direction-nav-row { flex: 0 0 auto; }
  .direction-nav-row .direction-nav-item { width: auto; }
  .direction-nav-all { margin-bottom: 0; }
  .indicator-main { overflow: visible; padding: 0; background: var(--indicator-paper); }
  .simple-filter__direction { flex: 1 1 100%; width: 100%; }
  .simple-filter__keyword { flex: 1 1 100%; }
  .simple-filter__status { flex: 1 1 130px; }
  .simple-filter__query { flex: 0 0 auto; }
  .expand-all-button { margin-left: 0; }
  .a-summary-years { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 420px) {
  .simple-filter { gap: 8px; padding: 10px; }
  .a-quant-summary, .indicator-card .a-quant-summary { grid-template-columns: minmax(0, 1fr); }
  .a-quant-summary > div { border-right: 0; }
  .a-summary-years { grid-template-columns: minmax(0, 1fr); }
  .header-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); width: 100%; }
  .header-actions .el-button { width: 100%; margin: 0; }
}
</style>
